package org.cedar.metadata.storage.controller

import com.datastax.driver.core.utils.UUIDs
import groovy.json.JsonSlurper
import io.restassured.RestAssured
import io.restassured.http.ContentType
import org.cedar.metadata.storage.Application
import org.cedar.metadata.storage.config.TestRabbitConfig
import org.cedar.metadata.storage.domain.MetadataRecord
import org.cedar.metadata.storage.domain.MetadataSchema
import org.cedar.metadata.storage.domain.MetadataSchemaRepository
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

import static org.hamcrest.Matchers.equalTo
import static org.hamcrest.Matchers.isA
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

@ActiveProfiles("test")
@SpringBootTest(classes = [Application, TestRabbitConfig], webEnvironment = RANDOM_PORT)
class SchemaApiSpec extends Specification {

  @Value('${local.server.port}')
  private String port

  @Value('${server.context-path:/}')
  private String contextPath

  @Value('${rabbitmq.queue}')
  String queueName

  @Autowired
  MetadataSchemaRepository metadataSchemaRepository

  @Autowired
  RabbitTemplate rabbitTemplate

  PollingConditions poller

  def setup() {
    poller = new PollingConditions(timeout: 10)
    RestAssured.baseURI = "http://localhost"
    RestAssured.port = port as Integer
    RestAssured.basePath = contextPath
  }

  def postBody = [
          "metadata_schema": "schemaFace",
          "json_schema": "{blah:blah}"
  ]

  def 'create, read, update, delete schema metadata'() {
    setup: 'define a schema metadata record'

    when: 'we post, a new record is create and returned in response'
    Map schemaMetadata = RestAssured.given()
            .body(postBody)
            .contentType(ContentType.JSON)
            .when()
            .post('/schemas')
            .then()
            .assertThat()
            .statusCode(201)
            .body('data[0].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[0].attributes.json_schema', equalTo(postBody.json_schema))
            .extract()
            .path('data[0].attributes')

    then: 'we can get it by id'
    RestAssured.given()
            .contentType(ContentType.JSON)
            .when()
            .get("/schemas/${schemaMetadata.id}")
            .then()
            .assertThat()
            .statusCode(200)
            .body('data[0].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[0].attributes.json_schema', equalTo(postBody.json_schema))


    when: 'we update the postBody with the id and new metadata'

    String updatedSchema = "different schema"
    Map updatedPostBody = schemaMetadata.clone()
    updatedPostBody.json_schema = updatedSchema

    then: 'we can update it (create a new version)'

    RestAssured.given()
            .body(updatedPostBody)
            .contentType(ContentType.JSON)
            .when()
            .put("/schemas/${schemaMetadata.id}")
            .then()
            .assertThat()
            .statusCode(200)
            .body('data[0].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[0].attributes.json_schema', equalTo(updatedSchema))

    and: 'we can get both versions'
    Map updatedRecord = RestAssured.given()
            .param('showVersions', true)
            .when()
            .get("/schemas/${schemaMetadata.id}")
            .then()
            .assertThat()
            .statusCode(200)
            .body('data.size', equalTo(2))

    //first one is the newest
            .body('data[0].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[0].attributes.json_schema', equalTo(updatedSchema))
    //second one is the original
            .body('data[1].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[1].attributes.json_schema', equalTo(postBody.json_schema))
            .extract()
            .path('data[0].attributes')

    then: 'submit the latest schema back with a delete method to delete it'
    //delete it
    RestAssured.given()
            .body(updatedRecord)
            .contentType(ContentType.JSON)
            .when()
            .delete("/schemas/${schemaMetadata.id}")
            .then()
            .assertThat()
            .statusCode(200)
            .body('data[0].meta.action', equalTo('delete'))
            .body('data[0].id', equalTo(schemaMetadata.id as String))

    and: 'it is gone, but we can get it with a a flag- showDeleted'
    RestAssured.given()
            .when()
            .get("/schemas/${schemaMetadata.id}")
            .then()
            .assertThat()
            .contentType(ContentType.JSON)
            .statusCode(404)  //should be a 404
            .body('data', equalTo(null))
            .body('errors', equalTo(['No records exist with id: ' + schemaMetadata.id.toString()]))

    RestAssured.given()
            .param('showDeleted', true)
            .when()
            .get("/schemas/${schemaMetadata.id}")
            .then()
            .assertThat()
            .statusCode(200)
            .body('data[0].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[0].attributes.json_schema', equalTo(updatedSchema))
            .body('data[0].attributes.deleted', equalTo(true))

    and: 'we can get all 3 back with showDeleted AND showVersions'
    RestAssured.given()
            .param('showDeleted', true)
            .param('showVersions', true)
            .when()
            .get("/schemas/${schemaMetadata.id}")
            .then()
            .assertThat()
            .statusCode(200)
            .body('data.size', equalTo(3))
            .body('data[0].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[0].attributes.json_schema', equalTo(updatedSchema))
            .body('data[0].attributes.deleted', equalTo(true))

            .body('data[1].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[1].attributes.json_schema', equalTo(updatedSchema))

            .body('data[2].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[2].attributes.json_schema', equalTo(postBody.json_schema))

    then: 'clean up the db, purge all 3 records by id'
    //delete all with that id
    RestAssured.given()
            .body(updatedRecord) //id in here
            .contentType(ContentType.JSON)
            .when()
            .delete('/schemas/purge')
            .then()
            .assertThat()
            .statusCode(200)
            .body('data.size', equalTo(3))


            .body('data[1].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[1].attributes.json_schema', equalTo(updatedSchema))

            .body('data[2].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[2].attributes.json_schema', equalTo(postBody.json_schema))

    and: 'finally, we should have sent 3 messages'

    List<String> actions = []

    poller.eventually {
      String m
      List<String> expectedActions = ['insert', 'update', 'delete']
      while (m = (rabbitTemplate.receive(queueName))?.getBodyContentAsString()) {
        def jsonSlurper = new JsonSlurper()
        def object = jsonSlurper.parseText(m)
        actions.add(object.data[0].meta.action)
        assert actions == expectedActions
      }
    }
  }

  def 'trigger recovery - only latest version is sent'(){
    setup:

    metadataSchemaRepository.deleteAll()

    when: 'we trigger the recovery process'

    MetadataRecord record = metadataSchemaRepository.save(new MetadataSchema(postBody))

    //create two records with same id
    UUID sharedId = UUIDs.timeBased()
    Map updatedPostBody = postBody.clone()
    updatedPostBody.id = sharedId
    MetadataRecord oldVersion = metadataSchemaRepository.save(new MetadataSchema(updatedPostBody))
    MetadataRecord latestVersion = metadataSchemaRepository.save(new MetadataSchema(updatedPostBody))

    RestAssured.given()
            .contentType(ContentType.JSON)
            .when()
            .put('/schemas/recover')
            .then()
            .assertThat()
            .statusCode(200)

    then:
    poller.eventually {
      String m
      while (m = (rabbitTemplate.receive(queueName))?.getBodyContentAsString()) {
        def jsonSlurper = new JsonSlurper()
        def object = jsonSlurper.parseText(m)
        assert (object.data[0] == record || object.data[0] == latestVersion) && !(object.data[0] == oldVersion)
      }
    }

  }

  def 'messages are sent with appropriate action'(){
    setup:

    metadataSchemaRepository.deleteAll()

    MetadataRecord original = metadataSchemaRepository.save(new MetadataSchema(postBody))

    Map updatedPostBody = postBody.clone()
    updatedPostBody.deleted = true
    MetadataSchema deletedVersion = new MetadataSchema(updatedPostBody)

    MetadataRecord deleted = metadataSchemaRepository.save(deletedVersion)

    when: 'we trigger the recovery process'
    RestAssured.given()
            .contentType(ContentType.JSON)
            .when()
            .put('/schemas/recover')
            .then()
            .assertThat()
            .statusCode(200)

    then:

    poller.eventually {
      String m
      while (m = (rabbitTemplate.receive(queueName))?.getBodyContentAsString()) {
        def jsonSlurper = new JsonSlurper()
        def object = jsonSlurper.parseText(m)
        if(object.data[0].meta.action == 'update'){
          assert object.data[0].id == original.id
        }
        if(object.data[0].meta.action == 'delete'){
          assert object.data[0].id == deleted.id
        }
      }
    }
  }

  def 'update with locking'() {
    when: 'define a collection metadata record'

    Map record = RestAssured.given()
            .body(postBody)
            .contentType(ContentType.JSON)
            .when()
            .post("/schemas")
            .then()
            .assertThat()
            .statusCode(201)
            .body('data[0].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[0].attributes.json_schema', equalTo(postBody.json_schema))
            .extract()
            .path('data[0].attributes')

    Long wrongDate =  record.last_update - 1000
    Long correctDate = record.last_update

    then: 'submit the it back with last_update as request param'

    RestAssured.given()
            .param('version', wrongDate)
            .body(record)
            .contentType(ContentType.JSON)
            .when()
            .put("/schemas/${record.id}")
            .then()
            .assertThat()
            .statusCode(409)
            .body('errors', isA(List))


    RestAssured.given()
            .param('version', correctDate)
            .body(postBody)
            .contentType(ContentType.JSON)
            .when()
            .put("/schemas/${record.id}")
            .then()
            .assertThat()
            .statusCode(200)
            .body('data[0].id', equalTo(record.id as String))
            .body('data[0].type', equalTo('schema'))
            .body('data[0].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[0].attributes.json_schema', equalTo(postBody.json_schema))
  }

  def 'update without locking'() {
    when: 'define a collection metadata record'

    Map record = RestAssured.given()
            .body(postBody)
            .contentType(ContentType.JSON)
            .when()
            .post("/schemas")
            .then()
            .assertThat()
            .statusCode(201)
            .body('data[0].type', equalTo('schema'))
            .body('data[0].attributes.metadata_schema', equalTo(postBody.metadata_schema))
            .body('data[0].attributes.json_schema', equalTo(postBody.json_schema))
            .extract()
            .path('data[0].attributes')

    Map updatedRecord = record.clone()
    updatedRecord.metadata_schema = "New metadata schema"

    then: 'submit it back without version request param '

    RestAssured.given()
            .body(updatedRecord)
            .contentType(ContentType.JSON)
            .when()
            .put("/schemas/${record.id}")
            .then()
            .assertThat()
            .statusCode(200)
            .body('data[0].type', equalTo('schema'))
            .body('data[0].attributes.metadata_schema', equalTo(updatedRecord.metadata_schema))
            .body('data[0].attributes.json_schema', equalTo(postBody.json_schema))

  }
}
