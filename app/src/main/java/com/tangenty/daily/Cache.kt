package com.tangenty.daily

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentMap

data class Cache(
    var latestDate: Int,
    var currentDate: Int,
    var lru: ConcurrentLinkedDeque<String>,
    var storiesCache: ConcurrentMap<String, Stories>
)

data class Stories(
    val date: String,
    val stories: List<Story>
)

data class Story(
    val title: String,
    val id: Int,
    val url: String,
    val images: List<String>,
    var imageBlob: String?,
    var body: String?
)
