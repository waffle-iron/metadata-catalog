package ncei.catalog.service

import ncei.catalog.domain.CollectionMetadata
import ncei.catalog.domain.CollectionMetadataRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import javax.servlet.http.HttpServletResponse

@Component
class CollectionService {

  @Autowired
  CollectionMetadataRepository collectionMetadataRepository

  Map save(CollectionMetadata granuleMetadata){
    Map saveDetails = [:]
    //get existing row if there is one
    Iterable<CollectionMetadata> result = collectionMetadataRepository.findByMetadataId(granuleMetadata.collection_id)

    //if we have a result, we want to 'update' row by inserting the same collection_id with a new last_update time
    if(result){
      collectionMetadataRepository.save(granuleMetadata)
      saveDetails.totalResultsUpdated = 1
      saveDetails.code = HttpServletResponse.SC_OK

    }else{ //create a new one
      collectionMetadataRepository.save(granuleMetadata)
      saveDetails.code = HttpServletResponse.SC_CREATED
    }

    saveDetails
  }
  
  List<CollectionMetadata> list(Map params){

    Boolean versions = params?.versions
    String collectionName = params?.collection_name
    String schema = params?.collection_schema
    UUID collection_id = params?.collection_id ? UUID.fromString(params.collection_id) : null

    Iterable<CollectionMetadata> allResults
    List<CollectionMetadata> metadataList = []

    if(collection_id){
      allResults = collectionMetadataRepository.findByMetadataId(collection_id)
    }
    else if(collectionName && schema){
      allResults = collectionMetadataRepository.findByCollectionNameAndSchema(collectionName, schema)
    }
    else if(collectionName){
      allResults = collectionMetadataRepository.findByCollectionName(collectionName)
    }
    else if (schema){
      allResults = collectionMetadataRepository.findBySchema(schema)
    }
    else{
      allResults = collectionMetadataRepository.findAll()
    }

    //get most recent or show all versions
    if(versions){
      allResults.each{ gm ->
        metadataList.add(gm)
      }
    }else{
      metadataList = getMostRecent(allResults)
    }

    metadataList
  }
  List<CollectionMetadata> getMostRecent(Iterable<CollectionMetadata> allResults){
    Map<String, CollectionMetadata> collectionMetadataMap = [:]
    List<CollectionMetadata> mostRecent
    allResults.each{ gm ->
      String metadataId = gm.collection_id as String
      if(collectionMetadataMap[metadataId]){
        if(collectionMetadataMap[metadataId].last_update < gm.last_update){
          collectionMetadataMap[metadataId] = gm
        }
      }else{
        collectionMetadataMap[metadataId] = gm
      }
    }
    mostRecent = collectionMetadataMap.collect{key, value ->
      value
    }
    mostRecent

  }

  def delete(UUID collection_id){
    Date timestamp = collectionMetadataRepository.findByMetadataId(collection_id as UUID).first().last_update as Date
    def result = collectionMetadataRepository.deleteByMetadataId(collection_id , timestamp)

  }
  
}
