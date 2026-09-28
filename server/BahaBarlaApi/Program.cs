using System.Data;
using System.Globalization;
using System.Net.Http.Headers;
using System.Text;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Hosting.WindowsServices;

// Baha barla bridge.
//
// Phones running Android 12+ cannot open a TLS session with SQL Server 2014
// RTM: the server only speaks TLS 1.0 and Android no longer implements it.
// Windows still does, so this service sits in the middle -- it runs a few fixed
// queries and hands the phone plain JSON.
//
// Every request carries the person's own SQL Server login (HTTP Basic), and the
// connection is opened with it. Which databases someone may see is therefore
// decided by SQL Server itself, set up in SSMS -- this service keeps no user
// list and never falls back to a login of its own.

var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    // A Windows service starts in system32. The appsettings files are read
    // while the builder is built, so the content root has to be right here --
    // setting it afterwards is too late and the service would run with empty
    // Sql settings.
    ContentRootPath = WindowsServiceHelpers.IsWindowsService() ? AppContext.BaseDirectory : default
});
builder.Services.AddWindowsService(options => options.ServiceName = "BahaBarlaApi");

// The SQL password belongs to this machine, not to the repository.
// appsettings.Local.json is git-ignored and overrides anything below it.
builder.Configuration.AddJsonFile("appsettings.Local.json", optional: true, reloadOnChange: true);

var settings = builder.Configuration.GetSection("Sql");
var port = builder.Configuration.GetValue("Api:Port", 8080);
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");

var app = builder.Build();
var log = app.Logger;

string SqlServer() => settings["Server"] ?? "";
//string SqlUser() => settings["User"] ?? "";
//string SqlPassword() => settings["Password"] ?? "";
string DefaultDatabase() => settings["DefaultDatabase"] ?? "";

string[] AllowedDatabases() =>
    settings.GetSection("AllowedDatabases").Get<string[]>() ?? Array.Empty<string>();

// The database name reaches us from the phone, so it never goes into a
// connection string unless it is one the operator listed here.
string? ResolveDatabase(string? requested)
{
    var name = string.IsNullOrWhiteSpace(requested) ? DefaultDatabase() : requested.Trim();
    var allowed = AllowedDatabases();
    if (allowed.Length == 0) return name;
    return allowed.Contains(name, StringComparer.OrdinalIgnoreCase) ? name : null;
}

// The login typed on the phone, sent as HTTP Basic. Null when the request
// carries none, which every endpoint answers with 401.
static SqlLogin? ReadLogin(HttpRequest request)
{
    if (!AuthenticationHeaderValue.TryParse(request.Headers.Authorization, out var header)
        || !"Basic".Equals(header.Scheme, StringComparison.OrdinalIgnoreCase)
        || string.IsNullOrEmpty(header.Parameter))
        return null;
    try
    {
        var pair = Encoding.UTF8.GetString(Convert.FromBase64String(header.Parameter));
        var colon = pair.IndexOf(':');
        if (colon <= 0) return null;
        return new SqlLogin(pair[..colon], pair[(colon + 1)..]);
    }
    catch (FormatException)
    {
        return null;
    }
}

string ConnectionString(string database, SqlLogin login) => new SqlConnectionStringBuilder
{
    DataSource = SqlServer(),
    InitialCatalog = database,
    UserID = login.User,
    Password = login.Password,
    // The whole point of this service: Windows can still negotiate the old
    // TLS the server insists on.
    Encrypt = false,
    TrustServerCertificate = true,
    ConnectTimeout = 10
}.ConnectionString;

// Matches the term against the product name, and against barcode and material
// code exactly; exact hits sort first. A material may carry several barcodes,
// so the card shows the first while EXISTS matches any of them.
const string SearchSql = """
    DECLARE @q NVARCHAR(200) = @term;
    DECLARE @like NVARCHAR(410) =
        '%' + REPLACE(REPLACE(REPLACE(@q, '[', '[[]'), '%', '[%]'), '_', '[_]') + '%';

    -- No exchange rate is read here: the dollar price is worked out on the
    -- phone from the rate typed into its Settings.
    SELECT TOP 50
        m.material_name                          AS name,
        m.material_code                          AS code,
        ISNULL(b.bar_barcode, '')                AS barcode,
        ISNULL(pr.sale_price, 0)                AS price,
        -- The purchase price in whole manats: the phone shows it disguised
        -- behind a random prefix.
        CAST(ROUND(ISNULL(pr.purchase_price, 0), 0) AS BIGINT) AS purchase,
        ISNULL(w.wh_name, '')                    AS warehouse,
        ISNULL(t.mat_whousetotal_amount, 0)      AS stock,
        m.material_id                            AS material_id,
        -- The picture itself is a blob, too heavy to send with every hit. The
        -- phone only learns here that one exists and then asks /image for it.
        CASE WHEN EXISTS (
            SELECT 1 FROM dbo.tbl_mg_images im WITH (NOLOCK)
            WHERE im.material_id = m.material_id AND DATALENGTH(im.image_pict) > 0
        ) THEN 1 ELSE 0 END                      AS has_image
    FROM dbo.tbl_mg_materials m WITH (NOLOCK)

    -- 1. Single Barcode Lookup
    OUTER APPLY (
        SELECT TOP 1 bb.bar_barcode
        FROM dbo.tbl_mg_barcode bb WITH (NOLOCK)
        WHERE bb.material_id = m.material_id
        ORDER BY bb.bar_id
    ) b

    -- 2. Consolidated Price Lookup (Sale + Purchase in one pass)
    OUTER APPLY (
        SELECT 
        MAX(CASE WHEN p.price_type_id = 2 THEN p.price_value END) AS sale_price,
        MAX(CASE WHEN p.price_type_id = 1 THEN p.price_value END) AS purchase_price
    FROM (
        SELECT price_type_id, price_value,
               ROW_NUMBER() OVER (
                   PARTITION BY price_type_id 
                   ORDER BY price_start_date DESC, price_id DESC
               ) AS rn
        FROM dbo.tbl_mg_mat_price WITH (NOLOCK)
        WHERE material_id = m.material_id 
          AND price_type_id IN (1, 2)
    ) p
    WHERE p.rn = 1
    ) pr

    -- 3. Optimized Warehouse Stock Lookup
    OUTER APPLY (
        SELECT TOP (1) tot.mat_whousetotal_amount, tot.wh_id
        FROM dbo.tbl_mg_material_total tot WITH (NOLOCK)
        WHERE tot.material_id = m.material_id
      AND (
        -- 1. Specific warehouse OR specific aggregate requested (@wh > 0 or @wh = -1)
        (@wh <> 0 AND tot.wh_id = @wh)
        OR 
        -- 2. No warehouse specified (@wh = 0): match -1 or any physical warehouse (> 0)
        (@wh = 0 AND tot.wh_id = -1)
      )
    ORDER BY 
        -- When @wh = 0, prioritize the aggregate -1 row first if it exists
        CASE WHEN @wh = 0 AND tot.wh_id = -1 THEN 0 ELSE 1 END ASC,
        CASE WHEN tot.mat_whousetotal_amount < 0 THEN -tot.mat_whousetotal_amount ELSE tot.mat_whousetotal_amount END DESC,
        tot.wh_id ASC
    ) t

    LEFT JOIN dbo.tbl_mg_whouse w WITH (NOLOCK) ON w.wh_id = t.wh_id

    -- 4. Single Pass Barcode Check for WHERE & ORDER BY
    OUTER APPLY (
        SELECT TOP (1) 1 AS matched
        FROM dbo.tbl_mg_barcode bx WITH (NOLOCK)
        WHERE bx.material_id = m.material_id 
          AND bx.bar_barcode = @q
    ) bc_match

    WHERE (
       m.material_code = @q
       OR m.material_name LIKE @like
       OR bc_match.matched = 1
       )
      -- With a warehouse chosen, only products that are in it are listed. t is
      -- already narrowed to that warehouse. The accounting program keeps a row
      -- for a product in a warehouse even once its stock there is gone, so the
      -- row alone proves nothing -- the amount has to be non-zero. Negative
      -- stock (sold ahead of the receipt) still counts as being there.
          AND ISNULL(t.mat_whousetotal_amount, 0) <> 0
    ORDER BY
        CASE 
            WHEN m.material_code = @q OR bc_match.matched = 1 THEN 0 
            ELSE 1 
        END,
        m.material_name
    """;

// The warehouses offered in the phone's drop-down.
const string WarehousesSql = """
    SELECT -1 as wh_id, 'Hemmesi' as wh_name
    UNION ALL
    SELECT wh_id, wh_name
    FROM dbo.tbl_mg_whouse WITH (NOLOCK)
    WHERE wh_id > 0 AND isenabled > 0
    ORDER BY wh_id
    """;

// The product photo. tbl_mg_images keeps it as a blob against the material;
// the accounting program allows several, and the card shows the newest.
const string ImageSql = """
    SELECT TOP 1 image_pict
    FROM dbo.tbl_mg_images WITH (NOLOCK)
    WHERE material_id = @id AND DATALENGTH(image_pict) > 0
    ORDER BY ISNULL(modify_date, '19000101') DESC, image_id DESC
    """;

// Replacing a photo taken on the phone. No material carries more than one row
// here, so the existing one is overwritten and a first photo is inserted.
// Inserting through a SELECT off the material means an unknown id writes
// nothing, and the caller is told so by the row count coming back as zero.
const string ImageUpsertSql = """
    UPDATE dbo.tbl_mg_images
    SET image_pict = @bytes, modify_date = GETDATE()
    WHERE material_id = @id;

    IF @@ROWCOUNT = 0
        INSERT INTO dbo.tbl_mg_images (image_pict, firm_id, material_id, arap_id, modify_date)
        SELECT @bytes, ISNULL(m.firm_id, 1), @id, 0, GETDATE()
        FROM dbo.tbl_mg_materials m
        WHERE m.material_id = @id;
    """;

// A phone camera shot, scaled down before it is sent, lands well under this.
// Anything bigger is refused rather than written into the accounting database.
const int MaxPhotoBytes = 4 * 1024 * 1024;

// Numbers are rendered the way the reference screens show them: 22815,00
static string Number(object raw) => raw switch
{
    null or DBNull => "",
    decimal d => d.ToString("N2", CultureInfo.GetCultureInfo("de-DE")),
    double d => d.ToString("N2", CultureInfo.GetCultureInfo("de-DE")),
    IConvertible c => Convert.ToDecimal(c, CultureInfo.InvariantCulture)
        .ToString("N2", CultureInfo.GetCultureInfo("de-DE")),
    _ => raw.ToString()?.Trim() ?? ""
};

static string Text(object raw) => raw is null or DBNull ? "" : (raw.ToString() ?? "").Trim();

// Whatever the operator pasted into the accounting program is what sits in the
// blob -- there is no column saying which format it is, so read the first bytes.
static string ImageContentType(byte[] b)
{
    if (b.Length >= 8 && b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return "image/png";
    if (b.Length >= 3 && b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF) return "image/jpeg";
    if (b.Length >= 6 && b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46) return "image/gif";
    if (b.Length >= 12 && b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46
        && b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) return "image/webp";
    if (b.Length >= 2 && b[0] == 0x42 && b[1] == 0x4D) return "image/bmp";
    return "application/octet-stream";
}

// SQL Server's reasons for turning a login away, sorted into the two things
// the phone tells a person. When the login exists but has no user in the
// database, 4060 arrives together with 18456, so access is checked first.
static string? Refusal(SqlException e)
{
    var numbers = e.Errors.Cast<SqlError>().Select(x => x.Number).ToHashSet();
    // 4060 cannot open database, 916 no access under this login,
    // 229/230 permission denied on a table or column.
    if (numbers.Overlaps([4060, 916, 229, 230])) return "no_access";
    // 18456 login failed, 18487/18488 password expired or must be changed.
    if (numbers.Overlaps([18456, 18487, 18488])) return "auth";
    return null;
}

static IResult Unauthorized(HttpContext http, string detail)
{
    // Lets a browser pointed at the bridge ask for the login too.
    http.Response.Headers.WWWAuthenticate = "Basic realm=\"BahaBarla\", charset=\"UTF-8\"";
    return Results.Json(new { error = "auth", detail }, statusCode: 401);
}

// Runs one piece of work under the caller's own SQL Server login and turns
// every way it can fail into an answer the phone can tell apart.
async Task<IResult> AsCaller(HttpContext http, string? db, string what,
    Func<SqlConnection, Task<IResult>> work)
{
    var login = ReadLogin(http.Request);
    if (login is null) return Unauthorized(http, "login required");

    var database = ResolveDatabase(db);
    if (database is null)
        return Results.BadRequest(new { error = "database_not_allowed" });

    var opened = false;
    try
    {
        await using var connection = new SqlConnection(ConnectionString(database, login));
        await connection.OpenAsync();
        opened = true;
        return await work(connection);
    }
    catch (SqlException e) when (Refusal(e) is { } refusal)
    {
        log.LogInformation("{What}: '{User}' refused in {Db} ({Refusal})",
            what, login.User, database, refusal);
        return refusal == "auth"
            ? Unauthorized(http, e.Message)
            : Results.Json(new { error = refusal, detail = e.Message }, statusCode: 403);
    }
    catch (Exception e)
    {
        log.LogWarning(e, "{What} failed for '{User}' in {Db}", what, login.User, database);
        // Not getting as far as a session means the server could not be
        // reached; failing after it means the query itself went wrong.
        var kind = opened ? "query" : "connect";
        return Results.Json(new { error = kind, detail = e.Message }, statusCode: 502);
    }
}

app.MapGet("/health", () => Results.Ok(new
{
    ok = true,
    service = "BahaBarlaApi",
    server = SqlServer(),
    defaultDatabase = DefaultDatabase()
}));

app.MapGet("/databases", () => Results.Ok(AllowedDatabases()));

// Checks a login before the phone stores it. Getting into the database is not
// enough on its own -- a login mapped without read rights would pass and then
// fail on the first search -- so it also reads the products table.
app.MapGet("/login", (HttpContext http, string? db) =>
    AsCaller(http, db, "login", async connection =>
    {
        await using var command = new SqlCommand(
            "SELECT TOP 0 material_id FROM dbo.tbl_mg_materials", connection) { CommandTimeout = 15 };
        await command.ExecuteNonQueryAsync();
        return Results.Ok(new { ok = true });
    }));

app.MapGet("/warehouses", (HttpContext http, string? db) =>
    AsCaller(http, db, "warehouses", async connection =>
    {
        await using var command = new SqlCommand(WarehousesSql, connection) { CommandTimeout = 15 };
        var warehouses = new List<object>();
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            warehouses.Add(new
            {
                id = Convert.ToInt32(reader["wh_id"]),
                name = Text(reader["wh_name"])
            });
        }
        return Results.Ok(warehouses);
    }));

app.MapGet("/search", (HttpContext http, string? q, string? db, int? wh) =>
{
    if (string.IsNullOrWhiteSpace(q))
        return Task.FromResult<IResult>(Results.BadRequest(new { error = "empty_term" }));

    return AsCaller(http, db, "search", async connection =>
    {
        await using var command = new SqlCommand(SearchSql, connection) { CommandTimeout = 15 };
        command.Parameters.AddWithValue("@term", q.Trim());
        command.Parameters.AddWithValue("@wh", wh ?? -1);

        var products = new List<object>();
        await using var reader = await command.ExecuteReaderAsync();
        while (await reader.ReadAsync())
        {
            products.Add(new
            {
                name = Text(reader["name"]),
                code = Text(reader["code"]),
                barcode = Text(reader["barcode"]),
                price = Number(reader["price"]),
                purchase = Text(reader["purchase"]),
                warehouse = Text(reader["warehouse"]),
                stock = Number(reader["stock"]),
                materialId = Convert.ToInt32(reader["material_id"]),
                hasImage = Convert.ToInt32(reader["has_image"]) == 1
            });
        }

        log.LogInformation("search '{Term}' in {Db}: {Count} hit(s)",
            q, connection.Database, products.Count);
        return Results.Ok(products);
    });
});

// One photo, by material id. Returning the bytes raw keeps the phone free of an
// image library -- it cannot reach Maven to add one.
app.MapGet("/image", (HttpContext http, int id, string? db) =>
{
    if (id <= 0) return Task.FromResult<IResult>(Results.NotFound());

    return AsCaller(http, db, "image", async connection =>
    {
        await using var command = new SqlCommand(ImageSql, connection) { CommandTimeout = 15 };
        command.Parameters.AddWithValue("@id", id);

        if (await command.ExecuteScalarAsync() is not byte[] bytes || bytes.Length == 0)
            return Results.NotFound();

        // A product photo is edited about never, so let the phone hold on to
        // it -- but only that phone, since it was fetched under a login.
        http.Response.Headers.CacheControl = "private, max-age=86400";
        return Results.File(bytes, ImageContentType(bytes));
    });
});

// Puts a photo taken on the phone against the material. The body is the image
// itself, raw -- no multipart, so the phone needs nothing beyond what it
// already uses to read. A login with read rights only is refused by SQL Server
// on the UPDATE, and the phone says so.
app.MapPost("/image", async (HttpContext http, int id, string? db) =>
{
    if (id <= 0) return Results.NotFound();
    if (ReadLogin(http.Request) is null) return Unauthorized(http, "login required");

    if (http.Request.ContentLength > MaxPhotoBytes)
        return Results.Json(new { error = "too_large" }, statusCode: 413);

    var buffer = new MemoryStream();
    await http.Request.Body.CopyToAsync(buffer);
    var bytes = buffer.ToArray();

    if (bytes.Length == 0)
        return Results.BadRequest(new { error = "empty_body" });
    if (bytes.Length > MaxPhotoBytes)
        return Results.Json(new { error = "too_large" }, statusCode: 413);
    // Only something that reads as a picture goes in; the column is what the
    // accounting program renders, and it has no way to refuse a bad blob.
    if (ImageContentType(bytes) == "application/octet-stream")
        return Results.BadRequest(new { error = "not_an_image" });

    return await AsCaller(http, db, "photo upload", async connection =>
    {
        await using var command = new SqlCommand(ImageUpsertSql, connection) { CommandTimeout = 30 };
        command.Parameters.AddWithValue("@id", id);
        command.Parameters.Add("@bytes", SqlDbType.VarBinary, -1).Value = bytes;

        // Nothing written means no material carries that id.
        if (await command.ExecuteNonQueryAsync() == 0) return Results.NotFound();

        log.LogInformation("photo replaced for material {Id} in {Db}, {Bytes} bytes",
            id, connection.Database, bytes.Length);
        return Results.Ok(new { ok = true });
    });
});

log.LogInformation("Baha barla bridge listening on port {Port}, SQL Server {Server}",
    port, SqlServer());
app.Run();

record SqlLogin(string User, string Password)
{
    // Keeps the password out of anything that ends up printing a login.
    public override string ToString() => User;
}
