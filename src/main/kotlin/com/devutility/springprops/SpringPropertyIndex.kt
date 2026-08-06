package com.devutility.springprops

import com.devutility.props.PropertyFileReader
import com.devutility.props.PropertyFileScanner
import com.devutility.props.PropertySnapshot
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager

/** A property value together with the profile it came from. */
data class ResolvedValue(val profile: String, val value: String, val fileName: String)

/**
 * Caches the parsed config files of a project.
 *
 * Inlay hints are recomputed constantly, so the previous approach — walk the filename index
 * and re-parse every `application*.{yml,properties}` for every single annotation — was far
 * too expensive on a real project. The whole set is parsed once and reused until the VFS
 * reports a change.
 */
@Service(Service.Level.PROJECT)
class SpringPropertyIndex(private val project: Project) {

    @Volatile
    private var stamp: Long = -1L

    @Volatile
    private var snapshots: List<PropertySnapshot> = emptyList()

    private fun snapshots(): List<PropertySnapshot> {
        val current = VirtualFileManager.getInstance().modificationCount
        if (current != stamp) {
            snapshots = PropertyFileScanner.scan(project)
                .filter { isSpringConfig(it.name) }
                .map { PropertyFileReader.read(project, it) }
            stamp = current
        }
        return snapshots
    }

    /**
     * Every value configured for [key], one per profile. Later files with the same profile
     * do not overwrite earlier ones — the first hit wins, mirroring how a single source is
     * normally what the developer cares about.
     */
    fun resolve(key: String): List<ResolvedValue> {
        if (key.isBlank()) return emptyList()
        val byProfile = LinkedHashMap<String, ResolvedValue>()
        for (snapshot in snapshots()) {
            val value = snapshot.value(key) ?: continue
            val profile = PropertyFileScanner.profileOf(snapshot.displayName)
            byProfile.putIfAbsent(profile, ResolvedValue(profile, value, snapshot.displayName))
        }
        return byProfile.values.sortedBy { if (it.profile == "default") "" else it.profile }
    }

    /**
     * Spring's relaxed binding: a field called `maxPoolSize` may be configured as
     * `max-pool-size`, `max_pool_size` or `maxpoolsize`. Try each spelling.
     */
    fun resolveRelaxed(prefix: String, name: String): List<ResolvedValue> {
        for (candidate in relaxedNames(name)) {
            val resolved = resolve(if (prefix.isEmpty()) candidate else "$prefix.$candidate")
            if (resolved.isNotEmpty()) return resolved
        }
        return emptyList()
    }

    /** How many configured keys bind under `prefix.`, per profile. */
    fun countUnderPrefix(prefix: String): Map<String, Int> {
        if (prefix.isBlank()) return emptyMap()
        val counts = LinkedHashMap<String, Int>()
        for (snapshot in snapshots()) {
            val n = snapshot.withPrefix(prefix).size
            if (n == 0) continue
            val profile = PropertyFileScanner.profileOf(snapshot.displayName)
            counts.merge(profile, n, Int::plus)
        }
        return counts
    }

    private fun relaxedNames(name: String): List<String> {
        val kebab = StringBuilder()
        for ((i, c) in name.withIndex()) {
            if (c.isUpperCase() && i > 0) kebab.append('-')
            kebab.append(c.lowercaseChar())
        }
        val k = kebab.toString()
        return listOf(name, k, k.replace('-', '_'), k.replace("-", "")).distinct()
    }

    private fun isSpringConfig(name: String): Boolean =
        (name.startsWith("application") || name.startsWith("bootstrap")) &&
            (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"))

    companion object {
        fun getInstance(project: Project): SpringPropertyIndex = project.service()
    }
}
