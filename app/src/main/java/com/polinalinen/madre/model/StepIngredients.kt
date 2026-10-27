package com.polinalinen.madre.model

/** Explicit editorial links, never a number match, stemmer or cross-section name guess. */
internal object StepIngredients {
    data class Part(val text:String, val ref:IngredientRef?=null, val amount:String="", val base:String="")
    private data class Word(val text:String,val name:String,val section:String?=null)
    private fun words(spec:String)=spec.split(';').map { entry ->
        val (text,target)=entry.split('=');val ref=target.split(':',limit=2)
        Word(text,ref.last(),ref.first().takeIf{ref.size==2})
    }
    // Only actual additions are linked. Greasing, dusting, feeding and negative instructions are not.
    private val links=mapOf(
        "home_bread" to mapOf(0 to "закваску=активной закваски;воду=тёплой воды;муку=муки пшеничной в/с",2 to "всю опару=main:опара (из предыдущего этапа);воды=main:тёплой воды;муки=main:пшеничной муки в/с",4 to "сахар=сахара;соль=соли;сливочное масло=мягкого сливочного масла"),
        "family_bread" to mapOf(0 to "пшеничной=пшеничной муки;ржаной муки=ржаной муки;воды=воды",2 to "соль=соли;сахар=сахара;масло=растительного масла;семена=семена (лён, подсолнечник, кунжут)",4 to "Левито Мадре=Левито Мадре активной"),
        "pirozhki" to mapOf(0 to "тёплое молоко=тёплого молока;закваску=активированной закваски;мёд=мёда (или сахара);муку=муки",2 to "тёплому молоку=тёплого молока;сахар=сахара;соль=соли;опару=опара (из предыдущего этапа);яйцо=яйцо;растительное масло=растительного масла;муку=муки"),
        "belyashi" to mapOf(0 to "Закваску=активированной закваски;водой=воды;молоком=молока;соль=соли;сахар=сахара;растительное масло=растительного масла;муку=пшеничной муки;сливочное масло=сливочного масла",5 to "начинку=main:начинки (мясо, лук)"),
        "ciabatta" to mapOf(0 to "закваску=активной закваски;муку=пшеничной муки;холодной воды=воды",2 to "соль=соли;оставшуюся холодную воду=воды",3 to "оливковое масло=оливкового масла"),
        "focaccia" to mapOf(0 to "закваски=закваски;воды=воды;муки=муки в/с",2 to "воду=холодной воды;опару=опара (из предыдущего этапа);всю муку=муки в/с;всю муку=цельнозерновой муки",4 to "мёд=мёда",5 to "соль=соли",6 to "оливковое масло=оливкового масла",9 to "маслом=main:оливковое масло, травы, помидор"),
        "pizza" to mapOf(0 to "ингредиенты=стартера (закваски);ингредиенты=воды;ингредиенты=муки",2 to "опару=опары (из предыдущего этапа);муку=муки;воды=воды",4 to "масло=оливкового масла"),
        "waffles" to mapOf(0 to "муку=пшеничной муки;соду=соды;соль=соли;сахар=сахара;закваску=активной закваски;яйца=яйца;молоко=молока;тёплое масло=сливочного масла"),
        "pancakes" to mapOf(2 to "закваске=активной закваски;молоко=молока;сахар=сахара;муку=пшеничной муки",4 to "соль=соли;соду=соды;яйца=яйца"),
        "cinnamon_buns" to mapOf(0 to "закваски=закваски Левито Мадре;муки=муки;воды=воды",2 to "муки=муки;молока=молока",4 to "яйца=яйца;масло=сливочного масла;сахар=сахара;соль=соли;ваниль=ваниль;муки=просеянной муки;опарой=опара 1 (из предыдущего этапа);опарой=опара 2 (из предыдущего этапа)",6 to "маслом=растопленного сливочного масла;корицей=корицы;сахаром=сахара (лучше тростникового)",11 to "маскарпоне=маскарпоне (или сметана 25%);пудрой=сахарной пудры;ванилью=ванильный экстракт"),
        "garlic_buns" to mapOf(0 to "закваску=Левито Мадре на пике активности;водой=воды;муку=муки пшеничной в/с;подсолнечное масло=подсолнечного масла;соль=соли;сахар=сахара;мягкое сливочное масло=сливочного масла")
    ).mapValues{(_,steps)->steps.mapValues{words(it.value)}}
    fun linkedSteps(recipe:Recipe)=links[recipe.id].orEmpty().keys
    private val token=Regex("\\{\\{([^|{}]+)\\|([^:{}]+):([^{}]+)\\}\\}\\s*")
    fun parts(recipe:Recipe,index:Int,portions:Int):List<Part> {
        val step=recipe.timeline[index];val source=step.description
        val bindings=RecipeScaler.scalableBindings(recipe)
        val tokens=token.findAll(source).toList()
        data class Hit(val start:Int,val end:Int,val part:Part)
        val hits=links[recipe.id]?.get(index).orEmpty().mapNotNull { word ->
            val ref=IngredientRef(word.section?:step.ingredientStage?:return@mapNotNull null,word.name)
            val ingredient=recipe.ingredients[ref.section]?.singleOrNull{it.name==ref.name}?:return@mapNotNull null
            val match=Regex("(?<![\\p{L}])${Regex.escape(word.text)}(?![\\p{L}])").findAll(source)
                .firstOrNull{ m -> tokens.none{m.range.first in it.range} }?:return@mapNotNull null
            val prefix=tokens.singleOrNull{it.range.last+1==match.range.first}
            if(prefix!=null && IngredientRef(prefix.groupValues[2],prefix.groupValues[3])!=ref)return@mapNotNull null
            var measured=ingredient
            var label=word.text
            if(prefix!=null) {
                val quantity=RecipeScaler.stepQuantities(prefix.value).singleOrNull()?:return@mapNotNull null
                measured=ingredient.copy(amount=quantity.amount,unit=quantity.unit)
            }
            // This named remainder is exactly the recipe's water minus the explicitly bound first addition.
            if(recipe.id=="ciabatta" && index==2 && ref.name=="воды") {
                measured=ingredient.copy(amount=ingredient.amount-RecipeScaler.stepQuantities(recipe.timeline[0].description).single{it.ref==ref}.amount)
            }
            if(links[recipe.id]?.get(index).orEmpty().count{it.text==word.text}>1)label=ingredient.name
            fun amount(factor:Double):String {
                if(!measured.scalable || measured.unit=="по вкусу")return "по вкусу"
                if(measured.refType=="all_of_section") {
                    val section=recipe.ingredients[measured.refSection]?:return "вся опара"
                    return "${section.sumOf{Math.round(it.gramsValue()*factor)}} г"
                }
                return RecipeScaler.scaledDisplayText(measured,factor).removeSuffix(" ${measured.name}")
            }
            Hit(prefix?.range?.first?:match.range.first,match.range.last+1,Part(label,ref,amount(portions.toDouble()),amount(1.0)))
        }.sortedBy{it.start}
        val result=mutableListOf<Part>();var cursor=0
        hits.groupBy{it.start to it.end}.forEach { (range,group) ->
            if(range.first<cursor)return@forEach
            if(range.first>cursor)result+=Part(RecipeScaler.scaledStepText(source.substring(cursor,range.first),portions.toDouble(),bindings))
            group.forEachIndexed{i,hit->if(i>0)result+=Part(" + ");result+=hit.part}
            cursor=range.second
        }
        if(cursor<source.length)result+=Part(RecipeScaler.scaledStepText(source.substring(cursor),portions.toDouble(),bindings))
        return result
    }
    fun text(parts:List<Part>)=parts.joinToString(""){if(it.ref==null)it.text else "${it.text} ${it.amount}"}
}
