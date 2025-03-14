package party.qwer.iris

import android.app.IActivityManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.ServiceManager
import android.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

// SendMsg : ye-seola/go-kdb

class Replier {
    companion object {
        private val binder: IBinder = ServiceManager.getService("activity")
        private val activityManager: IActivityManager = IActivityManager.Stub.asInterface(binder)
        private val messageChannel = Channel<SendMessageRequest>(Channel.CONFLATED)
        private val coroutineScope = CoroutineScope(Dispatchers.IO)
        private var messageSenderJob: Job? = null
        private val mutex = Mutex()

        init {
            startMessageSender()
        }

        fun startMessageSender() {
            coroutineScope.launch {
                if (messageSenderJob?.isActive == true) {
                    messageSenderJob?.cancelAndJoin()
                }
                messageSenderJob = launch {
                    for (request in messageChannel) {
                        try {
                            mutex.withLock {
                                request.send()
                                delay(Configurable.messageSendRate)
                            }
                        } catch (e: Exception) {
                            System.err.println("Error sending message from channel: $e")
                        }
                    }
                }
            }
        }

        fun restartMessageSender() {
            startMessageSender()
        }

        private fun sendMessageInternal(referer: String, chatId: Long, msg: String) {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.kakao.talk", "com.kakao.talk.notification.NotificationActionService"
                )
                putExtra("noti_referer", referer)
                putExtra("chat_id", chatId)
                action = "com.kakao.talk.notification.REPLY_MESSAGE"

                val results = Bundle().apply {
                    putCharSequence("reply_message", msg)
                }

                val remoteInput = RemoteInput.Builder("reply_message").build()
                val remoteInputs = arrayOf(remoteInput)
                RemoteInput.addResultsToIntent(remoteInputs, this, results)
            }

            startService(intent)
        }

        fun sendMessage(referer: String, chatId: Long, msg: String) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest { sendMessageInternal(referer, chatId, msg) })
            }
        }


        fun sendPhoto(room: Long, base64ImageDataString: String) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendPhotoInternal(
                        room, base64ImageDataString
                    )
                })
            }
        }

        fun sendMultiplePhotos(room: Long, base64ImageDataStrings: List<String>) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMultiplePhotosInternal(
                        room, base64ImageDataStrings
                    )
                })
            }
        }

        private fun sendPhotoInternal(room: Long, base64ImageDataString: String) {
            sendMultiplePhotosInternal(room, listOf(base64ImageDataString))
        }

        private fun sendMultiplePhotosInternal(room: Long, base64ImageDataStrings: List<String>) {
            val picDir = File(IMAGE_DIR_PATH).apply {
                if (!exists()) {
                    mkdirs()
                }
            }

            val uris = base64ImageDataStrings.map {
                val decodedImage = Base64.decode(it, Base64.DEFAULT)
                val timestamp = System.currentTimeMillis().toString()

                val imageFile = File(picDir, "$timestamp.png").apply {
                    writeBytes(decodedImage)
                }

                val imageUri = Uri.fromFile(imageFile)
                mediaScan(imageUri)
                imageUri
            }

            if (uris.isEmpty()) {
                System.err.println("No image URIs created, cannot send multiple photos.")
                return
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage("com.kakao.talk")
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra("key_id", room)
                putExtra("key_type", 1)
                putExtra("key_from_direct_share", true)
            }

            try {
                startActivity(intent)
            } catch (e: Exception) {
                System.err.println("Error starting activity for sending multiple photos: $e")
                throw e
            }
        }


        internal fun interface SendMessageRequest {
            suspend fun send()
        }

        private fun mediaScan(uri: Uri) {
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = uri
            }
            broadcastIntent(mediaScanIntent)
        }

        private fun broadcastIntent(intent: Intent) {
            activityManager.broadcastIntent(
                null, intent, null, null, 0, null, null, null, -1, null, false, false, -2
            )
        }

        private fun startActivity(intent: Intent) {
            activityManager.startActivityAsUserWithFeature(
                null,
                "com.android.shell",
                null,
                intent,
                intent.type,
                null,
                null,
                0,
                0,
                null,
                null,
                -2
            )
        }

        private fun startService(intent: Intent) {
            activityManager.startService(
                null, intent, intent.type, false, "com.android.shell", null, -2
            )
        }
    }
}