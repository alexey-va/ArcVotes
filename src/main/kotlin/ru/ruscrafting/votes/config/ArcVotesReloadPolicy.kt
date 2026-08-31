package ru.ruscrafting.votes.config

/** Returns settings whose change cannot be applied without a full restart. */
fun restartRequiredFields(
    current: ArcVotesSettings,
    candidate: ArcVotesSettings,
): List<String> = buildList {
    if (current.serverId != candidate.serverId) add("server-id")
    if (current.sql != candidate.sql) add("mysql")
    if (current.http.enabled && candidate.http.enabled) {
        if (current.http.bindAddress != candidate.http.bindAddress) add("http.bind-address")
        if (current.http.port != candidate.http.port) add("http.port")
        if (current.http.workerThreads != candidate.http.workerThreads) add("http.worker-threads")
        if (current.http.queueCapacity != candidate.http.queueCapacity) add("http.queue-capacity")
    }
}
