package com.streamvault.domain.sports.provider

/** Adapter types and account instances remain separate. Empty registries are valid. */
class SportsProviderRegistry(
    factories: Collection<SportsProviderFactory>,
    releasePolicy: SportsProviderReleasePolicy = SportsProviderReleasePolicy(),
) {
    init { require(factories.map { it.descriptor.typeId }.distinct().size == factories.size) }

    // Filter factories, not just descriptors: persisted configurations cannot bypass release gates.
    private val byType = factories.filter { releasePolicy.permits(it.descriptor) }
        .associateBy { it.descriptor.typeId }

    val descriptors: List<SportsProviderDescriptor>
        get() = byType.values.map { it.descriptor }.sortedBy { it.typeId }

    fun descriptor(typeId: String): SportsProviderDescriptor? = byType[typeId]?.descriptor

    fun capabilities(config: SportsProviderConfig): Set<SportsCapability> = runCatching {
        byType[config.typeId]?.capabilitiesFor(config).orEmpty()
    }.getOrDefault(emptySet())

    fun displayName(config: SportsProviderConfig): String? = runCatching {
        byType[config.typeId]?.displayNameFor(config)
    }.getOrNull()

    fun prepare(config: SportsProviderConfig): SportsProviderConfig {
        val factory = requireNotNull(byType[config.typeId])
        val descriptor = factory.descriptor
        require(config.configurationVersion <= descriptor.configurationVersion)
        val migrated = if (config.configurationVersion < descriptor.configurationVersion) {
            factory.migrate(config)
        } else config
        require(migrated.instanceId == config.instanceId && migrated.typeId == config.typeId)
        require(migrated.configurationVersion == descriptor.configurationVersion)
        require(migrated.baseUrl == null || descriptor.supportsCustomBaseUrl ||
            migrated.baseUrl == descriptor.defaultBaseUrl)
        factory.capabilitiesFor(migrated)
        return migrated
    }

    fun create(config: SportsProviderConfig): SportsProvider {
        val prepared = prepare(config)
        val factory = byType.getValue(prepared.typeId)
        return factory.create(prepared).also {
            require(it.instanceId == prepared.instanceId && it.descriptor == factory.descriptor)
            it.validateCapabilities()
        }
    }

    /** Stable ordering independent of factory registration or storage iteration order. */
    fun eligible(
        configs: Collection<SportsProviderConfig>,
        capability: SportsCapability,
    ): List<SportsProviderConfig> = configs.asSequence()
        .filter { it.enabled && capability in capabilities(it) }
        .sortedWith(compareBy<SportsProviderConfig> { it.priority }.thenBy { it.instanceId })
        .toList()
}
