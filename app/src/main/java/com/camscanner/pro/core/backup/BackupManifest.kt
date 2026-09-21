package com.camscanner.pro.core.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.Serializable

data class BackupPageItem(
    val pageIndex: Int,
    val filename: String,
    val filterType: String = "MAGIC_COLOR",
    val rotationDegrees: Int = 0,
    val ocrText: String? = null
) : Serializable

data class BackupDocItem(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    val category: String = "ALL",
    val ocrSnippet: String? = null,
    val pages: List<BackupPageItem>
) : Serializable

data class BackupManifest(
    val version: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val appVersion: String = "1.2.0",
    val documents: List<BackupDocItem>
) : Serializable {

    fun toJson(): String {
        val root = JSONObject()
        root.put("version", version)
        root.put("createdAt", createdAt)
        root.put("appVersion", appVersion)

        val docsArray = JSONArray()
        for (doc in documents) {
            val docObj = JSONObject()
            docObj.put("id", doc.id)
            docObj.put("title", doc.title)
            docObj.put("createdAt", doc.createdAt)
            docObj.put("updatedAt", doc.updatedAt)
            docObj.put("pageCount", doc.pageCount)
            docObj.put("category", doc.category)
            docObj.put("ocrSnippet", doc.ocrSnippet ?: "")

            val pagesArray = JSONArray()
            for (p in doc.pages) {
                val pageObj = JSONObject()
                pageObj.put("pageIndex", p.pageIndex)
                pageObj.put("filename", p.filename)
                pageObj.put("filterType", p.filterType)
                pageObj.put("rotationDegrees", p.rotationDegrees)
                pageObj.put("ocrText", p.ocrText ?: "")
                pagesArray.put(pageObj)
            }
            docObj.put("pages", pagesArray)
            docsArray.put(docObj)
        }
        root.put("documents", docsArray)
        return root.toString(2)
    }

    companion object {
        fun fromJson(jsonStr: String): BackupManifest {
            val root = JSONObject(jsonStr)
            val version = root.optInt("version", 1)
            val createdAt = root.optLong("createdAt", System.currentTimeMillis())
            val appVersion = root.optString("appVersion", "1.2.0")

            val docsArray = root.optJSONArray("documents") ?: JSONArray()
            val docsList = mutableListOf<BackupDocItem>()

            for (i in 0 until docsArray.length()) {
                val docObj = docsArray.getJSONObject(i)
                val docId = docObj.optLong("id")
                val title = docObj.optString("title", "Document")
                val docCreated = docObj.optLong("createdAt")
                val docUpdated = docObj.optLong("updatedAt")
                val pageCount = docObj.optInt("pageCount", 1)
                val category = docObj.optString("category", "ALL")
                val snippet = docObj.optString("ocrSnippet").ifBlank { null }

                val pagesArray = docObj.optJSONArray("pages") ?: JSONArray()
                val pagesList = mutableListOf<BackupPageItem>()

                for (j in 0 until pagesArray.length()) {
                    val pObj = pagesArray.getJSONObject(j)
                    val pIndex = pObj.optInt("pageIndex", j)
                    val filename = pObj.optString("filename")
                    val filter = pObj.optString("filterType", "MAGIC_COLOR")
                    val rot = pObj.optInt("rotationDegrees", 0)
                    val ocr = pObj.optString("ocrText").ifBlank { null }

                    pagesList.add(BackupPageItem(pIndex, filename, filter, rot, ocr))
                }

                docsList.add(
                    BackupDocItem(
                        id = docId,
                        title = title,
                        createdAt = docCreated,
                        updatedAt = docUpdated,
                        pageCount = pageCount,
                        category = category,
                        ocrSnippet = snippet,
                        pages = pagesList
                    )
                )
            }

            return BackupManifest(version, createdAt, appVersion, docsList)
        }
    }
}
