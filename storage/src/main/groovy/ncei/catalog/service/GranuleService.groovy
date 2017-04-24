package ncei.catalog.service

import ncei.catalog.domain.GranuleMetadata
import ncei.catalog.domain.GranuleMetadataRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

import javax.servlet.http.HttpServletResponse

@Component
class GranuleService {

  @Autowired
  GranuleMetadataRepository granuleMetadataRepository

  Map save(List<GranuleMetadata> granuleMetadataList){
    Map saveDetails = [recordsCreated:0, results : []]

    granuleMetadataList.each{ granuleMetadata ->

      //get existing row if there is one
      Iterable<GranuleMetadata> result = granuleMetadataRepository.findByMetadataId(granuleMetadata.granule_id)

      saveDetails.results.add(granuleMetadataRepository.save(granuleMetadata))

      //if we have a result, we want to let the user know it 'updated'
      if(result){
        saveDetails.totalResultsUpdated = saveDetails.totalResultsUpdated ?
                saveDetails.totalResultsUpdated + 1
                : 1
        saveDetails.code = HttpServletResponse.SC_OK

      }else{ //create a new one
        saveDetails.recordsCreated = saveDetails.recordsCreated ?
                saveDetails.recordsCreated + 1
                : 1
        saveDetails.code = HttpServletResponse.SC_CREATED
      }

    }

    saveDetails
  }

  //This might need optimization.
  //You cant delete anything without the primary key, so we first have to find rows with matching dataset
  Map purge(Map params){
    Map purgeDetails = [:]
    purgeDetails.searchTerms = params
    String dataset
    int count = 0

    if(!params.dataset) {
      purgeDetails.message = 'A [dataset] parameter is required to purge'
      purgeDetails.totalResultsDeleted = count
      purgeDetails.code = HttpServletResponse.SC_BAD_REQUEST
      return purgeDetails
    }else{
      dataset = params.dataset
    }

    List items = granuleMetadataRepository.findByDataset(dataset)
    items.each{
      granuleMetadataRepository.delete(it.tracking_id)
      count++
    }

    purgeDetails.totalResultsDeleted = count
    purgeDetails.code = HttpServletResponse.SC_OK
    purgeDetails
  }

  List<GranuleMetadata> list(Map params){
    Boolean versions = params?.versions
    String dataset = params?.dataset
    String schema = params?.granule_schema
    List<String> granule_ids = params?.granule_ids?.tokenize(',')
    Iterable<GranuleMetadata> allResults
    List<GranuleMetadata> metadataList = []

    if (granule_ids) {
      granule_ids.each { id ->
        allResults = allResults ?
                allResults + granuleMetadataRepository.findByMetadataId(UUID.fromString(id))
                : granuleMetadataRepository.findByMetadataId(UUID.fromString(id))
      }
    }
    else if(dataset && schema){
      allResults = granuleMetadataRepository.findByDatasetAndSchema(dataset, schema)
    }
    else if(dataset){
      allResults = granuleMetadataRepository.findByDataset(dataset)
    }
    else if (schema){
      allResults = granuleMetadataRepository.findBySchema(schema)
    }
    else{
      allResults = granuleMetadataRepository.findAll()
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

  List<GranuleMetadata> getMostRecent(Iterable<GranuleMetadata> allResults){
    Map<String, GranuleMetadata> granuleMetadataMap = [:]
    List<GranuleMetadata> mostRecent
    allResults.each{ gm ->
      String metadataId = gm.granule_id as String
      if(granuleMetadataMap[metadataId]){
        if(granuleMetadataMap[metadataId].last_update < gm.last_update){
          granuleMetadataMap[metadataId] = gm
        }
      }else{
        granuleMetadataMap[metadataId] = gm
      }
    }
    mostRecent = granuleMetadataMap.collect{key, value ->
      value
    }
    mostRecent

  }

  def delete(UUID granule_id){
    Date timestamp = granuleMetadataRepository.findByMetadataId(granule_id as UUID).first().last_update as Date
    def result = granuleMetadataRepository.deleteByMetadataId(granule_id , timestamp)

  }

}
