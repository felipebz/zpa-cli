/**
 * Z PL/SQL Analyzer
 * Copyright (C) 2015-2026 Felipe Zorzo
 * mailto:felipe AT felipezorzo DOT com DOT br
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package br.com.felipezorzo.zpa.cli.rules

import com.felipebz.zpa.rules.RuleKey

object RuleKeyParser {
    fun parse(key: String): RuleKey {
        if (key.isBlank() || key != key.trim() || key.count { it == ':' } > 1) {
            throw invalidKey(key)
        }

        val separator = key.indexOf(':')
        if (separator == -1) {
            return RuleKey("", key)
        }

        val repository = key.substring(0, separator)
        val rule = key.substring(separator + 1)
        if (repository.isBlank() || rule.isBlank()) {
            throw invalidKey(key)
        }

        return RuleKey(repository, rule)
    }

    private fun invalidKey(key: String): IllegalArgumentException {
        return IllegalArgumentException(
            "Invalid rule key '$key'. Expected 'rule' or 'repository:rule'."
        )
    }
}
