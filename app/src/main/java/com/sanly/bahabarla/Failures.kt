package com.sanly.bahabarla

import android.content.Context
import com.sanly.bahabarla.data.ApiClient

/** What to tell a person when the bridge could not do what they asked. */
fun Context.failureText(failure: ApiClient.Outcome.Failure): String = when (failure.kind) {
    ApiClient.ErrorKind.NOT_CONFIGURED -> getString(R.string.err_no_settings)
    ApiClient.ErrorKind.CONNECT -> getString(R.string.err_connect)
    ApiClient.ErrorKind.AUTH -> getString(R.string.err_auth)
    ApiClient.ErrorKind.NO_ACCESS -> getString(R.string.err_no_access)
    ApiClient.ErrorKind.QUERY -> getString(R.string.err_query, failure.detail)
}
