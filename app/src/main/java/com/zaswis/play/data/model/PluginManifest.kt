package com.zaswis.play.data.model

import com.google.gson.annotations.SerializedName

data class PluginRepositoryManifest(
    @SerializedName("repositoryName") val repositoryName: String = "Community Extensions",
    @SerializedName("repositoryUrl") val repositoryUrl: String = "",
    @SerializedName("plugins") val plugins: List<PluginInfo> = emptyList()
)

data class PluginInfo(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("version") val version: String = "1.0.0",
    @SerializedName("siteUrl") val siteUrl: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("iconUrl") val iconUrl: String = "",
    @SerializedName("category") val category: String = "Movies & Series",
    @SerializedName("downloadUrl") val downloadUrl: String = "",
    @SerializedName("className") val className: String = "",
    @SerializedName("isEnabled") var isEnabled: Boolean = true,
    @SerializedName("isInstalled") var isInstalled: Boolean = false
)
