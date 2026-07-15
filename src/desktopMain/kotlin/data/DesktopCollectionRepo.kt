package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class DesktopCollectionRepo : CollectionRepo {
    private val _rows = MutableStateFlow<List<CollectionRow>>(emptyList())
    override val rows: StateFlow<List<CollectionRow>> = _rows.asStateFlow()

    init { refresh() }

    private fun refresh() {
        _rows.value = transaction {
            CollectionRows.selectAll().map { row ->
                CollectionRow(
                    groupName = row[CollectionRows.groupName],
                    groupType = row[CollectionRows.groupType],
                    cardName  = row[CollectionRows.cardName],
                    quantity  = row[CollectionRows.quantity],
                )
            }
        }
    }

    override suspend fun replaceRows(rows: List<CollectionRow>): Unit = withContext(Dispatchers.IO) {
        transaction {
            CollectionRows.deleteAll()
            if (rows.isNotEmpty()) {
                CollectionRows.batchInsert(rows) { r ->
                    this[CollectionRows.groupName] = r.groupName
                    this[CollectionRows.groupType] = r.groupType
                    this[CollectionRows.cardName]  = r.cardName
                    this[CollectionRows.quantity]  = r.quantity
                }
            }
        }
        refresh()
    }
}
