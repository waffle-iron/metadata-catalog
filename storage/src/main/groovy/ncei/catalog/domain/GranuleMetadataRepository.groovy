package ncei.catalog.domain

import org.springframework.data.cassandra.repository.CassandraRepository
import org.springframework.data.cassandra.repository.Query

interface GranuleMetadataRepository extends CassandraRepository<GranuleMetadata> {

  @Query("Select * from GranuleMetadata where metadata_id =?0 and last_update=?1")
  Iterable<GranuleMetadata> findByIdAndLastUpdate(UUID id, Date lastUpdate)

  @Query("Select * from GranuleMetadata where metadata_id =?0 LIMIT 1")
  Iterable<GranuleMetadata> findByMetadataId(UUID id)

//    @Query("SELECT*FROM GranuleMetadata WHERE filename=?0 LIMIT ?1")
//    Iterable<GranuleMetadata> findByFilename(String filename, Integer limit)
//
//    @Query("SELECT*FROM GranuleMetadata WHERE filename=?0 AND tracking_id<?1 LIMIT ?2")
//    Iterable<GranuleMetadata> findByFilenameFrom(String filename, String from, Integer limit)
//
  @Query("SELECT*FROM GranuleMetadata WHERE dataset=?0")
  Iterable<GranuleMetadata> findByDataset(String dataset)

  @Query("SELECT*FROM GranuleMetadata WHERE granule_schema=?0")
  Iterable<GranuleMetadata> findBySchema(String schema)

  @Query("SELECT*FROM GranuleMetadata WHERE dataset =?0 AND granule_schema=?1")
  Iterable<GranuleMetadata> findByDatasetAndSchema(String dataset, String schema)

  //this is not working
  @Query("SELECT DISTINCT metadata_id FROM GranuleMetadata WHERE dataset=?0")
  Iterable<GranuleMetadata> findDistinctTrackingIdsByDataset(String dataset)


  @Query("SELECT metadata_id FROM GranuleMetadata WHERE metadata_id=?0 LIMIT 1")
  Iterable<GranuleMetadata> findLatestByMetadataId(UUID metadata_id)

  @Query("DELETE FROM GranuleMetadata WHERE metadata_id =?0 AND last_update=?1")
  Iterable<GranuleMetadata> deleteByMetadataId(UUID id, Date lastUpdate)

  @Query("INSERT GranuleMetadata USING TTL 20 WHERE metadata_id =?0 AND last_update=?1")
  Iterable<GranuleMetadata> updateWithTTL(UUID id, Date lastUpdate)



}


