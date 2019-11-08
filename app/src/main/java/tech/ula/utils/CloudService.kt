package tech.ula.utils

import com.squareup.moshi.Moshi
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject



class CloudService {
    private val baseUrl = "http://9679867b.ngrok.io"
    private var client = OkHttpClient()
    private val jsonType = MediaType.parse("application/json; charset=utf-8")

    fun loginAndGetBearerToken(email: String, password: String): String {
        val jsonString = JSONObject()
                .put("email", email)
                .put("password", password)
                .toString()

        val body = RequestBody.create(jsonType, jsonString)
        val request = Request.Builder()
                .url("$baseUrl/login")
                .post(body)
                .build()

        val requestType = Login(request)
        return sendRequest(requestType)
    }

    fun getBoxes(bearerToken: String): String {
        val request = Request.Builder()
                .url("$baseUrl/boxes")
                .get()
                .addHeader("Authorization: Bearer ", bearerToken)
                .build()

        val requestType = GetBoxes(request)
        return sendRequest(requestType)
    }

    private fun makeBox(bearerToken: String): String {
        val body = RequestBody.create(jsonType, "")
        val request = Request.Builder()
                .url("$baseUrl/boxes")
                .addHeader("Authorization: Bearer ", bearerToken)
                .post(body)
                .build()

        val requestType = GetBoxes(request)
        return sendRequest(requestType)
    }

    private fun sendRequest(requestType: RequestType): String {
        val request = requestType.httpRequest
        val response = client.newCall(request).execute()
        val jsonString = response.body()?.string() ?: ""

        if (jsonString.isNotEmpty()) {
            return when (requestType) {
                is Login -> {
                    val credentials = requestType.jsonAdapter.fromJson(jsonString) as Credentials
                    credentials.access_token
                }
                is GetBoxes -> {
                    val boxes = requestType.jsonAdapter.fromJson(jsonString) as Boxes
                    boxes.data.first().attributes.name
                }
                is MakeBox -> {
                    val newBox = requestType.jsonAdapter.fromJson(jsonString) as Boxes
                    newBox.data.first().attributes.ip
                }
            }
        }

        return jsonString
    }
}

class Credentials {
    val access_token: String = ""
}

class Box {
    val type: String = ""
    val attributes: BoxAttributes = BoxAttributes()
    val id: String = ""
}

class BoxAttributes {
    val name: String = ""
    val ip: String = ""

}

class Boxes {
    val data: List<Box> = listOf()
}

sealed class RequestType(val httpRequest: Request) {
    val moshi = Moshi.Builder().build()
}

data class Login(val request: Request) : RequestType(httpRequest = request) {
    val jsonAdapter = moshi.adapter<Credentials>(Credentials::class.java)
}

data class GetBoxes(val request: Request) : RequestType(httpRequest = request) {
    val jsonAdapter = moshi.adapter<Credentials>(Boxes::class.java)
}

data class MakeBox(val request: Request) : RequestType(httpRequest = request) {
    val jsonAdapter = moshi.adapter<Credentials>(Boxes::class.java)
}


package tech.ula.viewmodel

import android.system.Os
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Delete
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.internal.http.HttpHeaders
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.Exception
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext

sealed class CloudState
sealed class LoginResult : CloudState() {
    object InProgress: LoginResult()
    object Success : LoginResult()
    object Failure : LoginResult()
}
sealed class ConnectResult : CloudState() {
    object InProgress : ConnectResult()
    data class Success(val ipAddress: String, val sshPort: Int) : ConnectResult()
    object PublicKeyNotFound : ConnectResult()
    data class RequestFailed(val message: String) : ConnectResult()
    object BoxCreateFailure : ConnectResult()
    object NullResponseFromCreate : ConnectResult()
    object ConnectFailure : ConnectResult()
    object BusyboxMissing : ConnectResult()
    object LinkFailed : ConnectResult()
}
sealed class DeleteResult : CloudState() {
    object InProgress : DeleteResult()
    data class IdRetrieved(val id: Int) : DeleteResult()
    data class Success(val id: Int) : DeleteResult()
    object ListRequestFailure : DeleteResult()
    data class ListResponseFailure(val message: String) : DeleteResult()
    object NullResponseFromList : DeleteResult()
    object DeleteRequestFailure : DeleteResult()
    data class DeleteResponseFailure(val message: String): DeleteResult()
}

@JsonClass(generateAdapter = true)
internal data class LoginResponse(
        @Json(name = "access_token") val accessToken: String,
        @Json(name = "expires-in") val expiresIn: Int,
        @Json(name = "refresh_token") val refreshToken: String,
        @Json(name = "token_type")val tokenType: String
)

@JsonClass(generateAdapter = true)
internal data class CreateResponse(
        val data: CreateData
)

@JsonClass(generateAdapter = true)
internal data class CreateData(
        val type: String,
        val attributes: CreateAttributes,
        val id: Int
)

@JsonClass(generateAdapter = true)
internal data class CreateAttributes(
        val sshPort: Int,
        val ipAddress: String
)

@JsonClass(generateAdapter = true)
internal data class ListResponse(val data: List<TunnelData>)

@JsonClass(generateAdapter = true)
internal data class TunnelData(val id: Int)

class CloudDemoViewModel : ViewModel(), CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onCleared() {
        job.cancel()
        super.onCleared()
    }

    private val client = OkHttpClient()
    private val baseUrl = "https://api.userland.tech/"
    private val jsonType = MediaType.parse("application/json")
    private val moshi = Moshi.Builder().build()
    private var accessToken = ""

    private val cloudState = MutableLiveData<CloudState>()

    fun getCloudState(): LiveData<CloudState> {
        return cloudState
    }

    fun handleLoginClick(email: String, password: String) = launch { withContext(Dispatchers.IO) {
        cloudState.postValue(LoginResult.InProgress)

        val request = createLoginRequest(email, password)

        val response = try {
            client.newCall(request).execute()
        } catch (err: Exception) {
            cloudState.postValue(LoginResult.Failure)
            return@withContext
        }
        if (!response.isSuccessful) {
            cloudState.postValue(LoginResult.Failure)
            return@withContext
        }

        val adapter = moshi.adapter(LoginResponse::class.java)
        val loginResponse = adapter.fromJson(response.body()!!.source())!!
        accessToken = loginResponse.accessToken
        cloudState.postValue(LoginResult.Success)
    } }

    fun handleConnectClick(filesDir: File) = launch { withContext(Dispatchers.IO) {
        val busyboxFile = File(filesDir, "/support/busybox")
        if (!busyboxFile.exists()) {
            cloudState.postValue(ConnectResult.BusyboxMissing)
            return@withContext
        }

        try {
            val shFile = File(filesDir, "/support/sh")
            if (shFile.exists()) shFile.delete()
            Os.symlink(busyboxFile.path, shFile.path)
        } catch (err: Exception) {
            cloudState.postValue(ConnectResult.LinkFailed)
            return@withContext
        }

        if (accessToken == "") {
            cloudState.postValue(LoginResult.Failure)
            return@withContext
        }

        cloudState.postValue(ConnectResult.InProgress)
        val request = createBoxCreateRequest(filesDir) ?: return@withContext

        val response = try {
            client.newCall(request).execute()
        } catch (err: Exception) {
            cloudState.postValue(ConnectResult.BoxCreateFailure)
            return@withContext
        }
        if (!response.isSuccessful) {
            cloudState.postValue(ConnectResult.RequestFailed(response.message()))
            return@withContext
        }

        val adapter = moshi.adapter(CreateResponse::class.java)
        val createResponse = try {
            adapter.fromJson(response.body()!!.source())!!
        } catch (err: NullPointerException) {
            cloudState.postValue(ConnectResult.NullResponseFromCreate)
            return@withContext
        }
        val ipAddress = createResponse.data.attributes.ipAddress
        val sshPort = createResponse.data.attributes.sshPort

        cloudState.postValue(ConnectResult.Success(ipAddress, sshPort))
    } }

    fun handleDeleteClick() = launch { withContext(Dispatchers.IO) {
        if (accessToken == "") {
            cloudState.postValue(LoginResult.Failure)
            return@withContext
        }

        cloudState.postValue(DeleteResult.InProgress)

        val request = createListRequest()
        val response = try {
            client.newCall(request).execute()
        } catch (err: Exception) {
            cloudState.postValue(DeleteResult.ListRequestFailure)
            return@withContext
        }
        if (!response.isSuccessful) {
            cloudState.postValue(DeleteResult.ListResponseFailure(response.message()))
            return@withContext
        }

        val listAdapter = moshi.adapter(ListResponse::class.java)
        val id = try {
            listAdapter.fromJson(response.body()!!.source())!!.data.first().id
        } catch (err: NullPointerException) {
            cloudState.postValue(DeleteResult.NullResponseFromList)
            return@withContext
        }

        val deleteRequest = createDeleteRequest(id)
        val deleteResponse = try {
            client.newCall(deleteRequest).execute()
        } catch (err: Exception) {
            cloudState.postValue(DeleteResult.DeleteRequestFailure)
            return@withContext
        }
        if (!deleteResponse.isSuccessful) {
            cloudState.postValue(DeleteResult.DeleteResponseFailure(response.message()))
            return@withContext
        }

        cloudState.postValue(DeleteResult.Success(id))
    } }

    private fun createLoginRequest(email: String, password: String): Request {
        val json = """
            {
                "email": "$email",
                "password": "$password"
            }
        """.trimIndent()

        val body = RequestBody.create(jsonType, json)
        return Request.Builder()
                .url("$baseUrl/login")
                .post(body)
                .build()
    }

    private fun createBoxCreateRequest(filesDir: File): Request? {
        val sshKeyFile = File(filesDir, "sshkey.pub")
        if (!sshKeyFile.exists()) {
            cloudState.postValue(ConnectResult.PublicKeyNotFound)
            return null
        }
        val sshKey = sshKeyFile.readText().trim()

        val json = """
            {
              "data": {
                "type": "box",
                "attributes": {
                  "port": ["http"],
                  "sshKey": "$sshKey"
                }
              }
            }
        """.trimIndent()

        val body = RequestBody.create(jsonType, json)
        return Request.Builder()
                .url("$baseUrl/boxes")
                .post(body)
                .addHeader("Authorization","Bearer $accessToken")
                .build()
    }

    private fun createListRequest(): Request {
        return Request.Builder()
                .url("$baseUrl/boxes")
                .addHeader("Authorization","Bearer $accessToken")
                .get()
                .build()
    }

    private fun createDeleteRequest(id: Int): Request {
        return Request.Builder()
                .url("$baseUrl/boxes/$id")
                .addHeader("Authorization","Bearer $accessToken")
                .delete()
                .build()
    }
}

class CloudDemoViewModelFactory : ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        return CloudDemoViewModel() as T
    }
