package dev.cloudsavesync.core.storage;

/**
 * The cloud account a provider is signed in to.
 *
 * @param id    stable, provider-specific account id (never shown to the player)
 * @param email for messages such as "Signed in as ..."; may be null
 */
public record AccountInfo(String id, String email) {
}
