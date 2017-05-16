package ncei.catalog.domain

import com.datastax.driver.core.utils.UUIDs
import org.springframework.cassandra.core.Ordering
import org.springframework.cassandra.core.PrimaryKeyType
import org.springframework.data.cassandra.mapping.Column
import org.springframework.data.cassandra.mapping.Indexed
import org.springframework.data.cassandra.mapping.PrimaryKeyColumn
import org.springframework.data.cassandra.mapping.Table

@Table(value = 'GranuleMetadata')
class GranuleMetadata extends MetadataRecord {

  @PrimaryKeyColumn(name = "id", ordinal = 0, type = PrimaryKeyType.PARTITIONED)
  UUID id

  @PrimaryKeyColumn(name = "last_update", ordinal = 1, type = PrimaryKeyType.CLUSTERED, ordering = Ordering.DESCENDING)
  Date last_update

  @Indexed
  String dataset

  @Indexed
  String granule_schema
  String tracking_id
  String filename
  String type
  String access_protocol
  Integer granule_size
  String granule_metadata
  String geometry
  List collections
  Boolean deleted

  GranuleMetadata() {
    this.id = UUIDs.timeBased()
    this.last_update = new Date()
    this.deleted = false
  }

  String toString() {
    "id: $id, last_update: $last_update, metadata: $granule_metadata"
  }
}