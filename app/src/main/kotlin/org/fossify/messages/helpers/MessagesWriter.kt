package org.fossify.messages.helpers

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Base64
import com.google.android.mms.pdu_alt.PduHeaders
import com.klinker.android.send_message.Utils
import org.fossify.commons.extensions.getLongValue
import org.fossify.commons.extensions.queryCursor
import org.fossify.commons.extensions.toast // debug
import org.fossify.commons.helpers.isRPlus
import org.fossify.messages.extensions.updateLastConversationMessage
import org.fossify.messages.models.MmsAddress
import org.fossify.messages.models.MmsBackup
import org.fossify.messages.models.MmsPart
import org.fossify.messages.models.SmsBackup

class MessagesWriter(private val context: Context) {
    private val INVALID_ID = -1L
    private val contentResolver = context.contentResolver
    private val modifiedThreadIds = mutableSetOf<Long>()
    private val threadIdCache = HashMap<String, Long>()

    fun debug(message: String) {
        System.err.println("XXX ${message}")
        context.toast(message)
    }

    fun writeSmsMessage(smsBackup: SmsBackup) {
        modifiedThreadIds.add(getOrCreateThreadId(smsBackup.address))
        if (!smsExist(smsBackup)) {
            contentResolver.insert(Sms.CONTENT_URI, smsToContentValuesWithThreadId(smsBackup))
        }
    }

    fun bulkWriteSmsMessages(smsBackups: List<SmsBackup>) {
        // the batch size must be at most 999 (see bulkSmsExist)
        val exist = bulkSmsExist(smsBackups)
        val newSmsBackups = smsBackups.filterIndexed { i, _ -> !exist[i] }
        smsBackups.forEach { modifiedThreadIds.add(getOrCreateThreadId(it.address)) }
        val contentValues = newSmsBackups.map { smsToContentValuesWithThreadId(it) }.toTypedArray()
        debug("Writing a batch of ${newSmsBackups.size} messages (skipping ${smsBackups.size - newSmsBackups.size} existing messages)")
        contentResolver.bulkInsert(Sms.CONTENT_URI, contentValues)
    }

    private fun smsExist(smsBackup: SmsBackup): Boolean {
        val uri = Sms.CONTENT_URI
        val projection = arrayOf(Sms._ID)
        val selection = "${Sms.DATE} = ? AND ${Sms.ADDRESS} = ? AND ${Sms.TYPE} = ?"
        val selectionArgs = arrayOf(smsBackup.date.toString(), smsBackup.address, smsBackup.type.toString())
        var exists = false
        context.queryCursor(uri, projection, selection, selectionArgs) {
            exists = it.count > 0
        }
        return exists
    }

    private fun bulkSmsExist(smsBackups: List<SmsBackup>): BooleanArray {
        // the number of messages must be at most 999, otherwise this might fail with:
        // android.database.sqlite.SQLiteException: too many SQL variables (code 1)
        // (it's a limit from older versions of SQLite, increased in https://sqlite.org/src/info/2def75693a8ae002)
        //
        // it's a tricky to make bulk queries with ContentResolver.query()
        // our approach is to query multiple timestamps in a single query
        // and then check the address and type columns separately
        // (the timestamps should be mostly unique, so this is efficient)
        debug("Bulk checking existing messages")
        val dates = smsBackups.map { it.date }.distinct()
        val existingMessages = HashSet<Triple<Long, String, Int>>() // a message is represented as a (date, address, type) triple
        if (!dates.isEmpty()) {
            val uri = Sms.CONTENT_URI
            val projection = arrayOf(Sms.DATE, Sms.ADDRESS, Sms.TYPE)
            val selectionParams = "?" + ",?".repeat(dates.size - 1)
            val selection = "${Sms.DATE} IN (${selectionParams})"
            val selectionArgs = dates.map { it.toString() }.toTypedArray()
            context.queryCursor(uri, projection, selection, selectionArgs, showErrors = true) {
                val date = it.getLong(0)
                val address = it.getString(1)
                val type = it.getInt(2)
                existingMessages.add(Triple(date, address, type))
            }
        }
        return smsBackups.map { existingMessages.contains(Triple(it.date, it.address, it.type)) }.toBooleanArray()
    }

    private fun smsToContentValuesWithThreadId(smsBackup: SmsBackup): ContentValues {
        val contentValues = smsBackup.toContentValues()
        val threadId = getOrCreateThreadId(smsBackup.address)
        contentValues.put(Sms.THREAD_ID, threadId)
        return contentValues
    }

    private fun getOrCreateThreadId(recipient: String): Long {
        return threadIdCache.getOrPut(recipient) {
            System.err.println("XXX Getting thread ID")
            Utils.getOrCreateThreadId(context, recipient)
        }
    }

    fun writeMmsMessage(mmsBackup: MmsBackup) {
        // 1. write mms msg, get the msg_id, check if mms exists before writing
        // 2. write parts - parts depend on the msg id, check if part exist before writing, write data if it is a non-text part
        // 3. write the addresses, address depends on msg id too, check if address exist before writing
        val contentValues = mmsBackup.toContentValues()
        val threadId = getMmsThreadId(mmsBackup)
        if (threadId != INVALID_ID) {
            contentValues.put(Mms.THREAD_ID, threadId)
            if (!mmsExist(mmsBackup)) {
                modifiedThreadIds.add(threadId)
                contentResolver.insert(Mms.CONTENT_URI, contentValues)
            }
            val messageId = getMmsId(mmsBackup)
            if (messageId != INVALID_ID) {
                mmsBackup.parts.forEach { writeMmsPart(it, messageId) }
                mmsBackup.addresses.forEach { writeMmsAddress(it, messageId) }
            }
        }
    }

    private fun getMmsThreadId(mmsBackup: MmsBackup): Long {
        val address = when (mmsBackup.messageBox) {
            Mms.MESSAGE_BOX_INBOX -> mmsBackup.addresses.firstOrNull { it.type == PduHeaders.FROM }?.address
            else -> mmsBackup.addresses.firstOrNull { it.type == PduHeaders.TO }?.address
        }
        return if (!address.isNullOrEmpty()) {
            Utils.getOrCreateThreadId(context, address)
        } else {
            INVALID_ID
        }
    }

    private fun getMmsId(mmsBackup: MmsBackup): Long {
        val threadId = getMmsThreadId(mmsBackup)
        val uri = Mms.CONTENT_URI
        val projection = arrayOf(Mms._ID)
        val selection = "${Mms.DATE} = ? AND ${Mms.DATE_SENT} = ? AND ${Mms.THREAD_ID} = ? AND ${Mms.MESSAGE_BOX} = ?"
        val selectionArgs = arrayOf(mmsBackup.date.toString(), mmsBackup.dateSent.toString(), threadId.toString(), mmsBackup.messageBox.toString())
        var id = INVALID_ID
        context.queryCursor(uri, projection, selection, selectionArgs) {
            id = it.getLongValue(Mms._ID)
        }

        return id
    }

    private fun mmsExist(mmsBackup: MmsBackup): Boolean {
        return getMmsId(mmsBackup) != INVALID_ID
    }

    @SuppressLint("NewApi")
    private fun mmsAddressExist(mmsAddress: MmsAddress, messageId: Long): Boolean {
        val addressUri = if (isRPlus()) Mms.Addr.getAddrUriForMessage(messageId.toString()) else Uri.parse("content://mms/$messageId/addr")
        val projection = arrayOf(Mms.Addr._ID)
        val selection = "${Mms.Addr.TYPE} = ? AND ${Mms.Addr.ADDRESS} = ? AND ${Mms.Addr.MSG_ID} = ?"
        val selectionArgs = arrayOf(mmsAddress.type.toString(), mmsAddress.address.toString(), messageId.toString())
        var exists = false
        context.queryCursor(addressUri, projection, selection, selectionArgs) {
            exists = it.count > 0
        }
        return exists
    }

    @SuppressLint("NewApi")
    private fun writeMmsAddress(mmsAddress: MmsAddress, messageId: Long) {
        if (!mmsAddressExist(mmsAddress, messageId)) {
            val addressUri = if (isRPlus()) {
                Mms.Addr.getAddrUriForMessage(messageId.toString())
            } else {
                Uri.parse("content://mms/$messageId/addr")
            }

            val contentValues = mmsAddress.toContentValues()
            contentValues.put(Mms.Addr.MSG_ID, messageId)
            contentResolver.insert(addressUri, contentValues)
        }
    }

    @SuppressLint("NewApi")
    private fun writeMmsPart(mmsPart: MmsPart, messageId: Long) {
        if (!mmsPartExist(mmsPart, messageId)) {
            val uri = Uri.parse("content://mms/${messageId}/part")
            val contentValues = mmsPart.toContentValues()
            contentValues.put(Mms.Part.MSG_ID, messageId)
            val partUri = contentResolver.insert(uri, contentValues)
            try {
                if (partUri != null) {
                    if (mmsPart.isNonText()) {
                        contentResolver.openOutputStream(partUri).use {
                            val arr = Base64.decode(mmsPart.data, Base64.DEFAULT)
                            it!!.write(arr)
                        }
                    }
                }
            } catch (e: Exception) {

            }
        }
    }

    @SuppressLint("NewApi")
    private fun mmsPartExist(mmsPart: MmsPart, messageId: Long): Boolean {
        val uri = Uri.parse("content://mms/${messageId}/part")
        val projection = arrayOf(Mms.Part._ID)
        val selection = "${Mms.Part.CONTENT_LOCATION} = ? AND ${Mms.Part.CONTENT_TYPE} = ? AND ${Mms.Part.MSG_ID} = ? AND ${Mms.Part.CONTENT_ID} = ?"
        val selectionArgs = arrayOf(mmsPart.contentLocation.toString(), mmsPart.contentType, messageId.toString(), mmsPart.contentId.toString())
        var exists = false
        context.queryCursor(uri, projection, selection, selectionArgs) {
            exists = it.count > 0
        }
        return exists
    }

    fun fixCoversationDates() {
        // TODO: document
        debug("Fixing dates for ${modifiedThreadIds.size} conversations")
        for (threadId in modifiedThreadIds) {
            context.updateLastConversationMessage(threadId)
        }
    }
}
