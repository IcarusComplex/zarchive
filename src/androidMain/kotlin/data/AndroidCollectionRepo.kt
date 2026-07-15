package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class AndroidCollectionRepo : CollectionRepo {
    private val queries get() = AndroidDatabase.instance.zArchiveDatabaseQueries

    private val _rows = MutableStateFlow<List<CollectionRow>>(emptyList())
    override val rows: StateFlow<List<CollectionRow>> = _rows.asStateFlow()

    init { refresh() }

    private fun refresh() {
        _rows.value = queries.selectAllCollectionRows().executeAsList().map { row ->
            CollectionRow(
                groupName = row.group_name,
                groupType = row.group_type,
                cardName  = row.card_name,
                quantity  = row.quantity.toInt(),
            )
        }
    }

    override suspend fun replaceRows(rows: List<CollectionRow>): Unit = withContext(Dispatchers.IO) {
        queries.clearCollectionRows()
        rows.forEach { r ->
            queries.insertCollectionRow(r.groupName, r.groupType, r.cardName, r.quantity.toLong())
        }
        refresh()
    }
}
