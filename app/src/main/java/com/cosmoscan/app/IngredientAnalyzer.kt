package com.cosmoscan.app

data class AnalysisReport(
    val ingredients: List<IngredientEntity>,
    val qualityScore: Int,
    val verdictTitle: String,
    val verdictSummary: String,
    val hasHarmful: Boolean,
    val isIneffectiveFormula: Boolean
)

object IngredientAnalyzer {

    fun extractIngredientNames(rawOcrText: String): List<String> {
        val cleanedText = rawOcrText
            .replace("\n", " ")
            .replace(Regex("\\s+"), " ")

        val startRegex = Regex("(ingredients|состав|inci)\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE)
        val match = startRegex.find(cleanedText)
        val relevantPart = if (match != null) {
            cleanedText.substring(match.range.last + 1)
        } else {
            cleanedText
        }

        val stopRegex = Regex("(способ применения|применение|годен до|изготовитель|volume|объем)", RegexOption.IGNORE_CASE)
        val stopMatch = stopRegex.find(relevantPart)
        val listOnly = if (stopMatch != null) {
            relevantPart.substring(0, stopMatch.range.first)
        } else {
            relevantPart
        }

        return listOnly.split(",", ";", "•")
            .map { item ->
                item.replace(Regex("\\(.*?\\)"), "")
                    .replace(Regex("[^a-zA-Zа-яА-ЯёЁ0-9\\s\\-]"), "")
                    .trim()
            }
            .filter { it.length in 3..55 }
            .distinct()
    }

    fun evaluateProduct(items: List<IngredientEntity>): AnalysisReport {
        if (items.isEmpty()) {
            return AnalysisReport(
                ingredients = emptyList(),
                qualityScore = 0,
                verdictTitle = "Состав не распознан",
                verdictSummary = "Наведите камеру чётко на блок «Состав / Ingredients».",
                hasHarmful = false,
                isIneffectiveFormula = false
            )
        }

        val harmfulCount = items.count { it.safety == "HARMFUL" }
        val cautionCount = items.count { it.safety == "CAUTION" }

        val thresholdIndex = items.indexOfFirst { it.isThresholdMarker }
        val activesBeforeThreshold = if (thresholdIndex == -1) {
            items.count { it.isActive }
        } else {
            items.take(thresholdIndex).count { it.isActive }
        }
        val activesAfterThreshold = if (thresholdIndex == -1) {
            0
        } else {
            items.drop(thresholdIndex + 1).count { it.isActive }
        }

        val isIneffective = (activesBeforeThreshold == 0 && activesAfterThreshold > 0) ||
                (items.none { it.isActive })

        var score = 100
        score -= harmfulCount * 25
        score -= cautionCount * 7
        if (isIneffective) score -= 30
        score = score.coerceIn(5, 100)

        val (title, summary) = when {
            harmfulCount > 0 -> Pair(
                "Не рекомендуется: есть агрессивные/вредные компоненты",
                "Обнаружено критичных веществ: $harmfulCount. Возможны раздражение, сухость или аллергическая реакция."
            )
            isIneffective -> Pair(
                "Слабый состав (маркетинговая пустышка)",
                "Полезные активы и масла находятся в конце списка (после консервантов/отдушки, т.е. их концентрация менее 1%). Выраженного эффекта не будет."
            )
            cautionCount > 2 -> Pair(
                "Среднее качество: база с компромиссами",
                "Есть компоненты, требующие осторожности ($cautionCount шт.), но критичных токсинов не найдено."
            )
            else -> Pair(
                "Хороший рабочий состав",
                "Активные компоненты стоят в начале списка (рабочая концентрация), агрессивные вещества отсутствуют."
            )
        }

        return AnalysisReport(
            ingredients = items,
            qualityScore = score,
            verdictTitle = title,
            verdictSummary = summary,
            hasHarmful = harmfulCount > 0,
            isIneffectiveFormula = isIneffective
        )
    }
}
