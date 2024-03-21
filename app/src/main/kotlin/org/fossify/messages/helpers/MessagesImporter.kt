package org.fossify.messages.helpers

import android.net.Uri
import android.util.Xml
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.dialogs.ImportMessagesDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.models.*
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream


class MessagesImporter(private val activity: SimpleActivity) {

    private val messageWriter = MessagesWriter(activity)
    private val config = activity.config
    private var messagesImported = 0
    private var messagesFailed = 0

    fun importMessages(uri: Uri) {
        try {
            val fileType = activity.contentResolver.getType(uri).orEmpty()
            val isXml = isXmlMimeType(fileType) || (uri.path?.endsWith("txt") == true && isFileXml(uri))
            if (isXml) {
                activity.toast(org.fossify.commons.R.string.importing)
                getInputStreamFromUri(uri)!!.importXml()
            } else {
                importJson(uri)
            }
        } catch (e: Exception) {
            activity.showErrorToast(e)
        }
    }

    private fun importJson(uri: Uri) {
        try {
            val jsonString = activity.contentResolver.openInputStream(uri)!!.use { inputStream ->
                inputStream.bufferedReader().readText()
            }

            val deserializedList = Json.decodeFromString<List<MessagesBackup>>(jsonString)
            if (deserializedList.isEmpty()) {
                activity.toast(org.fossify.commons.R.string.no_entries_for_importing)
                return
            }
            ImportMessagesDialog(activity, deserializedList)
        } catch (e: SerializationException) {
            activity.toast(org.fossify.commons.R.string.invalid_file_format)
        } catch (e: IllegalArgumentException) {
            activity.toast(org.fossify.commons.R.string.invalid_file_format)
        } catch (e: Exception) {
            activity.showErrorToast(e)
        }
    }

    fun restoreMessages(messagesBackup: List<MessagesBackup>, callback: (ImportResult) -> Unit) {
        ensureBackgroundThread {
            try {
                messageWriter.debug("Importing backup with ${messagesBackup.size} messages")
                if (config.importSms) {
                    messagesBackup.filterIsInstance<SmsBackup>().chunked(999) { chunk ->
                        try {
                            messageWriter.bulkWriteSmsMessages(chunk)
                            messagesImported += chunk.size
                        } catch (e: Exception) {
                            activity.showErrorToast(e)
                            messagesFailed += chunk.size
                        }
                    }
                }
                if (config.importMms) {
                    messagesBackup.filterIsInstance<MmsBackup>().forEach { mmsBackup ->
                        System.err.println("XXX Importing an MMS")
                        try {
                            messageWriter.writeMmsMessage(mmsBackup)
                            messagesImported++
                        } catch (e: Exception) {
                            activity.showErrorToast(e)
                            messagesFailed++
                        }
                    }
                }
                messageWriter.debug("Finished import")
                refreshMessages()
            } catch (e: Exception) {
                activity.showErrorToast(e)
            }

            callback.invoke(
                when {
                    messagesImported == 0 && messagesFailed == 0 -> ImportResult.IMPORT_NOTHING_NEW
                    messagesFailed > 0 && messagesImported > 0 -> ImportResult.IMPORT_PARTIAL
                    messagesFailed > 0 -> ImportResult.IMPORT_FAIL
                    else -> ImportResult.IMPORT_OK
                }
            )
        }
    }

    private fun InputStream.importXml() {
        try {
            bufferedReader().use { reader ->
                val xmlParser = Xml.newPullParser().apply {
                    setInput(reader)
                }

                xmlParser.nextTag()
                xmlParser.require(XmlPullParser.START_TAG, null, "smses")

                var depth = 1
                while (depth != 0) {
                    when (xmlParser.next()) {
                        XmlPullParser.END_TAG -> depth--
                        XmlPullParser.START_TAG -> depth++
                    }

                    if (xmlParser.eventType != XmlPullParser.START_TAG) {
                        continue
                    }

                    try {
                        if (xmlParser.name == "sms") {
                            if (config.importSms) {
                                val message = xmlParser.readSms()
                                messageWriter.writeSmsMessage(message)
                                messagesImported++
                            } else {
                                xmlParser.skip()
                            }
                        } else {
                            xmlParser.skip()
                        }
                    } catch (e: Exception) {
                        activity.showErrorToast(e)
                        messagesFailed++
                    }
                }
                refreshMessages()
            }
            when {
                messagesFailed > 0 && messagesImported > 0 -> activity.toast(org.fossify.commons.R.string.importing_some_entries_failed)
                messagesFailed > 0 -> activity.toast(org.fossify.commons.R.string.importing_failed)
                else -> activity.toast(org.fossify.commons.R.string.importing_successful)
            }
        } catch (_: Exception) {
            activity.toast(org.fossify.commons.R.string.invalid_file_format)
        }
    }

    private fun XmlPullParser.readSms(): SmsBackup {
        require(XmlPullParser.START_TAG, null, "sms")

        return SmsBackup(
            subscriptionId = 0,
            address = getAttributeValue(null, "address"),
            body = getAttributeValue(null, "body"),
            date = getAttributeValue(null, "date").toLong(),
            dateSent = getAttributeValue(null, "date").toLong(),
            locked = getAttributeValue(null, "locked").toInt(),
            protocol = getAttributeValue(null, "protocol"),
            read = getAttributeValue(null, "read").toInt(),
            status = getAttributeValue(null, "status").toInt(),
            type = getAttributeValue(null, "type").toInt(),
            serviceCenter = getAttributeValue(null, "service_center")
        )
    }

    private fun XmlPullParser.skip() {
        if (eventType != XmlPullParser.START_TAG) {
            throw IllegalStateException()
        }
        var depth = 1
        while (depth != 0) {
            when (next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }

    private fun getInputStreamFromUri(uri: Uri): InputStream? {
        return try {
            activity.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }
    }

    private fun isFileXml(uri: Uri): Boolean {
        val inputStream = getInputStreamFromUri(uri)
        return inputStream?.bufferedReader()?.use { reader ->
            reader.readLine()?.startsWith("<?xml") ?: false
        } ?: false
    }

    private fun isXmlMimeType(mimeType: String): Boolean {
        return mimeType.equals("application/xml", ignoreCase = true) || mimeType.equals("text/xml", ignoreCase = true)
    }

    private fun isJsonMimeType(mimeType: String): Boolean {
        return mimeType.equals("application/json", ignoreCase = true)
    }
}
