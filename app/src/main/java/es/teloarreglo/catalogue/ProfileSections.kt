package es.teloarreglo.catalogue

import java.util.Locale

/** Blocks of the public profile, in reading order (#47). */
enum class ProfileSection { COVER, IDENTITY, DESCRIPTION, PRICES, REVIEWS, SERVICES, COVERAGE, ADDRESS, LANGUAGES, PHONE }

/**
 * What decides the choice (prices and reviews) comes right after who the professional is; the contact details
 * follow. Sections without data are left out: no address, Spanish only, or reviews not available.
 */
fun profileSections(profile: PublicProfile, showReviews: Boolean): List<ProfileSection> = buildList {
    add(ProfileSection.COVER)
    add(ProfileSection.IDENTITY)
    add(ProfileSection.DESCRIPTION)
    add(ProfileSection.PRICES)
    if (showReviews) add(ProfileSection.REVIEWS)
    add(ProfileSection.SERVICES)
    add(ProfileSection.COVERAGE)
    if (!profile.businessAddress.isNullOrBlank()) add(ProfileSection.ADDRESS)
    if (profile.languages.any { Locale.forLanguageTag(it).language != "es" }) add(ProfileSection.LANGUAGES)
    add(ProfileSection.PHONE)
}
