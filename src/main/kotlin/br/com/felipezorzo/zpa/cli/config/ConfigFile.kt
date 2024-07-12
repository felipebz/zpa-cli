package br.com.felipezorzo.zpa.cli.config

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

@JsonIgnoreProperties(value = ["\$schema"])
data class ConfigFile(
    val rules: Map<String, RuleConfiguration>
)

@JsonDeserialize(using = RuleCategoryDeserializer::class)
@JsonSerialize(using = RuleCategorySerializer::class)
class RuleConfiguration {
    var options: RuleOptions = RuleOptions()
}

enum class RuleLevel {
    @JsonProperty("on")
    ON,
    @JsonProperty("off")
    OFF,
    @JsonProperty("blocker")
    BLOCKER,
    @JsonProperty("critical")
    CRITICAL,
    @JsonProperty("major")
    MAJOR,
    @JsonProperty("minor")
    MINOR,
    @JsonProperty("info")
    INFO
}

class RuleOptions {
    lateinit var level: RuleLevel
    var parameters: Map<String, String> = emptyMap()
}

class RuleCategoryDeserializer : JsonDeserializer<RuleConfiguration>() {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): RuleConfiguration {
        val node: JsonNode = p.codec.readTree(p)
        val ruleConfiguration = RuleConfiguration()
        val mapper = jacksonObjectMapper()

        if (node.isTextual) {
            ruleConfiguration.options.level = RuleLevel.valueOf(node.asText().uppercase())
        } else if (node.isObject) {
            ruleConfiguration.options = mapper.treeToValue(node, RuleOptions::class.java)
        }

        return ruleConfiguration
    }
}

class RuleCategorySerializer : JsonSerializer<RuleConfiguration>() {
    override fun serialize(value: RuleConfiguration, gen: JsonGenerator, serializers: SerializerProvider) {
        val mapper = jacksonObjectMapper()

        if (value.options.parameters.isEmpty()) {
            gen.writeString(value.options.level.toString())
        } else {
            gen.writeTree(mapper.valueToTree(value.options))
        }
    }
}
