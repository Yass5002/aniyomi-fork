package mihon.domain.extensionrepo.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import mihon.domain.extensionrepo.model.ExtensionRepo

@Serializable
data class ExtensionRepoMetaDto(
    @SerialName("index_v2")
    val indexV2: String? = null,
    val meta: ExtensionRepoDto? = null,
)

@Serializable
data class ExtensionRepoDto(
    val name: String,
    val shortName: String? = null,
    val website: String,
    val signingKeyFingerprint: String,
)

fun ExtensionRepoMetaDto.toExtensionRepo(baseUrl: String): ExtensionRepo {
    val repoMeta = requireNotNull(meta) { "Missing repository metadata" }
    return ExtensionRepo(
        baseUrl = baseUrl,
        name = repoMeta.name,
        shortName = repoMeta.shortName,
        website = repoMeta.website,
        signingKeyFingerprint = repoMeta.signingKeyFingerprint,
    )
}
