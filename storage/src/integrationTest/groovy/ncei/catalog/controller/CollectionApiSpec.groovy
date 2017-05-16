package ncei.catalog.controller

import groovy.json.JsonSlurper
import io.restassured.RestAssured
import io.restassured.http.ContentType
import ncei.catalog.Application
import ncei.catalog.domain.CollectionMetadata
import ncei.catalog.domain.CollectionMetadataRepository
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cassandra.core.cql.CqlIdentifier
import org.springframework.data.cassandra.core.CassandraAdminOperations
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import com.datastax.driver.core.Cluster
import com.datastax.driver.core.Session

import static org.hamcrest.Matchers.equalTo
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

@SpringBootTest(classes = [Application], webEnvironment = RANDOM_PORT)
class CollectionApiSpec extends Specification {

  @Autowired
  CollectionMetadataRepository collectionMetadataRepository

  @Autowired
  RabbitTemplate rabbitTemplate

  @Value('${local.server.port}')
  private String port

  @Value('${server.context-path:/}')
  private String contextPath

  PollingConditions poller

  @Autowired
  private CassandraAdminOperations adminTemplate

  final static String DATA_TABLE_NAME = 'CollectionMetadata'

  def setup() {
    poller = new PollingConditions(timeout: 10)
    RestAssured.baseURI = "http://localhost"
    RestAssured.port = port as Integer
    RestAssured.basePath = contextPath

    adminTemplate.createTable(
        true, CqlIdentifier.cqlId(DATA_TABLE_NAME),
        CollectionMetadata.class, new HashMap<String, Object>())
  }

  public static final String KEYSPACE_CREATION_QUERY = "CREATE KEYSPACE IF NOT EXISTS metacat WITH replication = { 'class': 'SimpleStrategy', 'replication_factor': '3' };"

  public static final String KEYSPACE_ACTIVATE_QUERY = "USE metacat;"

  def setupSpec() {
    final Cluster cluster = Cluster.builder().addContactPoints("127.0.0.1").withPort(9042).build()
    final Session session = cluster.connect()
    session.execute(KEYSPACE_CREATION_QUERY)
    session.execute(KEYSPACE_ACTIVATE_QUERY)
    Thread.sleep(5000)
  }

  def cleanup() {
    adminTemplate.dropTable(CqlIdentifier.cqlId(DATA_TABLE_NAME))
  }

  def postBody = [
      "collection_name"    : "collectionFace",
      "collection_schema"  : "a collection schema",
      "type"               : "fos",
      "collection_metadata": "{blah:blah}",
      "geometry" : "point()"
  ]

  def 'create and read'() {
    setup: 'define a collection metadata record'

    when: 'we post, a new record is create and returned in response'
    Map collectionMetadata = RestAssured.given()
        .body(postBody)
        .contentType(ContentType.JSON)
        .when()
        .post('/collections')
        .then()
        .assertThat()
        .statusCode(201)  //should be a 201
        .body('data[0].attributes.collection_name', equalTo(postBody.collection_name))
        .body('data[0].attributes.collection_schema', equalTo(postBody.collection_schema))
        .body('data[0].attributes.collection_metadata', equalTo(postBody.collection_metadata))
        .body('data[0].attributes.geometry', equalTo(postBody.geometry))
        .body('data[0].attributes.type', equalTo(postBody.type))
        .extract()
        .path('data[0].attributes')

    then: 'we can get it by id'
    RestAssured.given()
        .contentType(ContentType.JSON)
        .when()
        .get("/collections/${collectionMetadata.id}")
        .then()
        .assertThat()
        .statusCode(200)
        .body('data[0].attributes.collection_name', equalTo(postBody.collection_name))
        .body('data[0].attributes.collection_schema', equalTo(postBody.collection_schema))
        .body('data[0].attributes.collection_metadata', equalTo(postBody.collection_metadata))
        .body('data[0].attributes.geometry', equalTo(postBody.geometry))
        .body('data[0].attributes.type', equalTo(postBody.type))
        .extract().body()

    then: 'finally, we should have sent a rabbit message'

    List<String> actions = []

    poller.eventually {
      String m
      List<String> expectedActions = ['insert']
      while (m = (rabbitTemplate.receive('index-consumer'))?.getBodyContentAsString()) {
        def jsonSlurper = new JsonSlurper()
        def object = jsonSlurper.parseText(m)
        actions.add(object.meta.action)
        assert actions == expectedActions
      }
    }

  }

  def 'update and read'() {
    setup: 'define a collection metadata record'
    CollectionMetadata collectionMetadata = collectionMetadataRepository.save(new CollectionMetadata(postBody))

    when: 'we update the postBody with the id and new metadata'

    println("~~~ $collectionMetadata")
    println("~~~~ ${collectionMetadata.asMap()}")

    String updatedMetadata = "different metadata"
    Map updatedPostBody = collectionMetadata.asMap().clone() as Map
    updatedPostBody.collection_metadata = updatedMetadata

    then: 'we can update the record (create a new version)'

    RestAssured.given()
        .body(updatedPostBody)
        .contentType(ContentType.JSON)
        .when()
        .put("/collections/${collectionMetadata.id}")
        .then()
        .assertThat()
        .statusCode(200)
        .body('data[0].id', equalTo(collectionMetadata.id as String))
        .body('data[0].type', equalTo('collection'))

        .body('data[0].attributes.id', equalTo(collectionMetadata.id as String))
        .body('data[0].attributes.collection_name', equalTo(postBody.collection_name))
        .body('data[0].attributes.collection_schema', equalTo(postBody.collection_schema))
        .body('data[0].attributes.collection_metadata', equalTo(updatedPostBody.collection_metadata))
        .body('data[0].attributes.geometry', equalTo(postBody.geometry))
        .body('data[0].attributes.type', equalTo(postBody.type))

    then: 'by default we only get the latest version'
    RestAssured.given()
        .when()
        .get("/collections/${collectionMetadata.id}")
        .then()
        .assertThat()
        .statusCode(200)
        .body('meta.totalResults', equalTo(1))
        .body('data[0].attributes.collection_name', equalTo(postBody.collection_name))
        .body('data[0].attributes.collection_schema', equalTo(postBody.collection_schema))
        .body('data[0].attributes.collection_metadata', equalTo(updatedMetadata))
        .body('data[0].attributes.geometry', equalTo(postBody.geometry))
        .body('data[0].attributes.type', equalTo(postBody.type))

    and: 'we can get both versions'
    RestAssured.given()
        .param('showVersions', true)
        .when()
        .get("/collections/${collectionMetadata.id}")
        .then()
        .assertThat()
        .statusCode(200)
        .body('meta.totalResults', equalTo(2))
    //first one is the newest
        .body('data[0].attributes.collection_name', equalTo(postBody.collection_name))
        .body('data[0].attributes.collection_schema', equalTo(postBody.collection_schema))
        .body('data[0].attributes.collection_metadata', equalTo(updatedMetadata))
        .body('data[0].attributes.geometry', equalTo(postBody.geometry))
        .body('data[0].attributes.type', equalTo(postBody.type))
    //second one is the original
        .body('data[1].attributes.collection_name', equalTo(postBody.collection_name))
        .body('data[1].attributes.collection_schema', equalTo(postBody.collection_schema))
        .body('data[1].attributes.collection_metadata', equalTo(postBody.collection_metadata))
        .body('data[1].attributes.geometry', equalTo(postBody.geometry))
        .body('data[1].attributes.type', equalTo(postBody.type))
        .extract()
        .path('data[0].attributes')

    then: 'finally, we should have sent a rabbit message'

    List<String> actions = []

    poller.eventually {
      String m
      List<String> expectedActions = ['update']
      while (m = (rabbitTemplate.receive('index-consumer'))?.getBodyContentAsString()) {
        def jsonSlurper = new JsonSlurper()
        def object = jsonSlurper.parseText(m)
        actions.add(object.meta.action)
        assert actions == expectedActions
      }
    }
  }

  def 'delete and read'() {
    setup: 'define a collection metadata record'
    CollectionMetadata collectionMetadata = collectionMetadataRepository.save(new CollectionMetadata(postBody))

    when: 'submit the latest collection back with a delete method to delete it'

    Map updatedPostBody = collectionMetadata.asMap().clone() as Map

    //delete it
    RestAssured.given()
        .body(updatedPostBody)
        .contentType(ContentType.JSON)
        .when()
        .delete("/collections/${collectionMetadata.id}")
        .then()
        .assertThat()
        .statusCode(200)
        .body('meta.message' as String, equalTo('Successfully deleted row with id: ' + collectionMetadata.id))

    then: 'it is gone, but we can get it with a a flag- showDeleted'
    RestAssured.given()
        .param('showVersions', true)
        .when()
        .get("/collections/${collectionMetadata.id}")
        .then()
        .assertThat()
        .contentType(ContentType.JSON)
        .statusCode(404)
        .body('data', equalTo(null))
        .body('errors', equalTo(['No results found.']))

    RestAssured.given()
        .param('showDeleted', true)
        .when()
        .get("/collections/${collectionMetadata.id}")
        .then()
        .assertThat()
        .statusCode(200)
        .body('meta.totalResults', equalTo(1))
        .body('data[0].attributes.collection_name', equalTo(postBody.collection_name))
        .body('data[0].attributes.collection_schema', equalTo(postBody.collection_schema))
        .body('data[0].attributes.collection_metadata', equalTo(postBody.collection_metadata))
        .body('data[0].attributes.geometry', equalTo(postBody.geometry))
        .body('data[0].attributes.type', equalTo(postBody.type))
        .body('data[0].attributes.deleted', equalTo(true))

    and: 'we can get everything back with showDeleted AND showVersions'
    RestAssured.given()
        .param('showDeleted', true)
        .param('showVersions', true)
        .when()
        .get("/collections/${collectionMetadata.id}")
        .then()
        .assertThat()
        .statusCode(200)
        .body('meta.code', equalTo(200))
        .body('meta.success', equalTo(true))
        .body('meta.action', equalTo('read'))
        .body('meta.totalResults', equalTo(2))

        .body('data[0].attributes.collection_name', equalTo(postBody.collection_name))
        .body('data[0].attributes.collection_schema', equalTo(postBody.collection_schema))
        .body('data[0].attributes.collection_metadata', equalTo(postBody.collection_metadata))
        .body('data[0].attributes.geometry', equalTo(postBody.geometry))
        .body('data[0].attributes.type', equalTo(postBody.type))
        .body('data[0].attributes.deleted', equalTo(true))

        .body('data[1].attributes.collection_name', equalTo(postBody.collection_name))
        .body('data[1].attributes.collection_schema', equalTo(postBody.collection_schema))
        .body('data[1].attributes.collection_metadata', equalTo(postBody.collection_metadata))
        .body('data[1].attributes.geometry', equalTo(postBody.geometry))
        .body('data[1].attributes.type', equalTo(postBody.type))

    then: 'finally, we should have sent a rabbit message'

    List<String> actions = []

    poller.eventually {
      String m
      List<String> expectedActions = ['delete']
      while (m = (rabbitTemplate.receive('index-consumer'))?.getBodyContentAsString()) {
        def jsonSlurper = new JsonSlurper()
        def object = jsonSlurper.parseText(m)
        actions.add(object.meta.action)
        assert actions == expectedActions
      }
    }

  }
}
