package com.aistudio.core.model

import java.util.ArrayList
import java.util.Collections

internal fun <T> immutableListSnapshot(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))
