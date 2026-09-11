using System.Globalization;
using Microsoft.Data.SqlClient;

// Baha barla bridge.
//
// Phones running Android 12+ cannot open a TLS session with SQL Server 2014
// RTM: the server only speaks TLS 1.0 and Android no longer implements it.
// Windows still does, so this service sits in the middle -- it holds the
// database credentials, runs one fixed query, and hands the phone plain JSON.

var builder = WebApplication.CreateBuilder(args);
builder.Host.UseWindowsService(options => options.ServiceName = "BahaBarlaApi");

// The SQL password belongs to this machine, not to the repository.
// appsettings.Local.json is git-ignored and overrides anything below it.
builder.Configuration.AddJsonFile("appsettings.Local.json", optional: true, reloadOnChange: true);

var settings = builder.Configuration.GetSection("Sql");
var port = builder.Configuration.GetValue("Api:Port", 8080);
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");

var app = builder.Build();
var log = app.Logger;

string SqlServer() => settings["Server"] ?? "";
string SqlUser() => settings["User"] ?? "";
string SqlPassword() => settings["Password"] ?? "";
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

string ConnectionString(string database) => new SqlConnectionStringBuilder
{
    DataSource = SqlServer(),
    InitialCatalog = database,
    UserID = SqlUser(),
    Password = SqlPassword(),
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

    SELECT TOP 50
        m.material_name                          AS name,
        m.material_code                          AS code,
        ISNULL(b.bar_barcode, '')                AS barcode,
        ISNULL(p.price_value, 0)                 AS price,
        ISNULL(w.wh_name, '')                    AS warehouse,
        ISNULL(t.mat_whousetotal_amount, 0)      AS stock
    FROM dbo.tbl_mg_materials m WITH (NOLOCK)
    OUTER APPLY (
        SELECT TOP 1 bb.bar_barcode
        FROM dbo.tbl_mg_barcode bb WITH (NOLOCK)
        WHERE bb.material_id = m.material_id
        ORDER BY bb.bar_id
    ) b
    OUTER APPLY (
        SELECT TOP 1 pr.price_value
        FROM dbo.tbl_mg_mat_price pr WITH (NOLOCK)
        WHERE pr.material_id = m.material_id
          AND pr.price_type_id = 2      -- tbl_mg_price_type: 2 = Satys (sale)
        ORDER BY pr.price_start_date DESC, pr.price_id DESC
    ) p
    OUTER APPLY (
        SELECT TOP 1 tot.mat_whousetotal_amount, tot.wh_id
        FROM dbo.tbl_mg_material_total tot WITH (NOLOCK)
        WHERE tot.material_id = m.material_id
          AND tot.wh_id > 0             -- skip the -1 all-warehouse aggregate
        ORDER BY ABS(tot.mat_whousetotal_amount) DESC, tot.wh_id ASC
    ) t
    LEFT JOIN dbo.tbl_mg_whouse w WITH (NOLOCK) ON w.wh_id = t.wh_id
    WHERE m.material_code = @q
       OR m.material_name LIKE @like
       OR EXISTS (
            SELECT 1 FROM dbo.tbl_mg_barcode bx WITH (NOLOCK)
            WHERE bx.material_id = m.material_id AND bx.bar_barcode = @q
       )
    ORDER BY
        CASE
            WHEN m.material_code = @q THEN 0
            WHEN EXISTS (
                SELECT 1 FROM dbo.tbl_mg_barcode bx WITH (NOLOCK)
                WHERE bx.material_id = m.material_id AND bx.bar_barcode = @q
            ) THEN 0
            ELSE 1
        END,
        m.material_name
    """;

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

app.MapGet("/health", () => Results.Ok(new
{
    ok = true,
    service = "BahaBarlaApi",
    server = SqlServer(),
    defaultDatabase = DefaultDatabase()
}));

app.MapGet("/databases", () => Results.Ok(AllowedDatabases()));

app.MapGet("/search", async (string? q, string? db) =>
{
    if (string.IsNullOrWhiteSpace(q))
        return Results.BadRequest(new { error = "empty_term" });

    var database = ResolveDatabase(db);
    if (database is null)
        return Results.BadRequest(new { error = "database_not_allowed" });

    try
    {
        await using var connection = new SqlConnection(ConnectionString(database));
        await connection.OpenAsync();

        await using var command = new SqlCommand(SearchSql, connection) { CommandTimeout = 15 };
        command.Parameters.AddWithValue("@term", q.Trim());

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
                warehouse = Text(reader["warehouse"]),
                stock = Number(reader["stock"])
            });
        }

        log.LogInformation("search '{Term}' in {Db}: {Count} hit(s)", q, database, products.Count);
        return Results.Ok(products);
    }
    catch (SqlException e)
    {
        log.LogWarning(e, "search failed");
        // 18456 is "login failed"; the phone shows a different message for it.
        var kind = e.Number == 18456 ? "auth" : "query";
        return Results.Json(new { error = kind, detail = e.Message }, statusCode: 502);
    }
    catch (Exception e)
    {
        log.LogWarning(e, "search failed");
        return Results.Json(new { error = "connect", detail = e.Message }, statusCode: 502);
    }
});

log.LogInformation("Baha barla bridge listening on port {Port}, SQL Server {Server}",
    port, SqlServer());
app.Run();
