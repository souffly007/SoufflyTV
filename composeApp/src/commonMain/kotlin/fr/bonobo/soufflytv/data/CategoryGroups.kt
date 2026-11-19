package fr.bonobo.soufflytv.data

import fr.bonobo.soufflytv.model.Category

/** Préfixe suivi d'un séparateur : « FR: Sport », « |UK| News », « [DE] Kino », « France - Sport ». */
private val SEPARATED_PREFIX = Regex("""^[\s\[(|#*■•►▶★]*([^\s:|\-–—\[\]()■•►▶★]{2,20})\s*[:|\-–—\])■•►▶★]""")

/** Code pays en majuscules suivi d'un espace : « FR Sport », « UK Movies ». */
private val CODE_PREFIX = Regex("""^[\s\[(|#*■•►▶★]*([A-Z]{2,3})\s+\S""")

/**
 * Groupe (souvent le pays) déduit du nom d'une catégorie, ou null.
 * Sert à masquer d'un coup toutes les catégories « UK », « DE », « AR »…
 */
fun categoryGroup(name: String): String? {
    SEPARATED_PREFIX.find(name)?.let { m ->
        val group = m.groupValues[1]
        val rest = name.substring(m.range.last + 1).trim()
        if (group.any { it.isLetter() } && rest.isNotEmpty()) return group.uppercase()
    }
    CODE_PREFIX.find(name)?.let { return it.groupValues[1] }
    return null
}

data class CategoryGroup(val name: String, val categories: List<Category>)

/** Groupes d'au moins deux catégories, les plus fournis en premier. */
fun groupCategories(categories: List<Category>): List<CategoryGroup> =
    categories
        .mapNotNull { c -> categoryGroup(c.name)?.let { it to c } }
        .groupBy({ it.first }, { it.second })
        .filter { it.value.size >= 2 }
        .map { (name, list) -> CategoryGroup(name, list) }
        .sortedWith(compareByDescending<CategoryGroup> { it.categories.size }.thenBy { it.name })
