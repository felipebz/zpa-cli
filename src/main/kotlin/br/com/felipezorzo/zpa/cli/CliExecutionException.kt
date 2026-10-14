package br.com.felipezorzo.zpa.cli

/** A failure of an external resource (such as the database) reported with a sanitized message and no stack trace. */
class CliExecutionException(message: String) : RuntimeException(message)
