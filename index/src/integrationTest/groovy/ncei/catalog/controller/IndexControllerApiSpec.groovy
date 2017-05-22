package ncei.catalog.controller

import io.restassured.RestAssured
import io.restassured.http.ContentType
import io.restassured.response.Response
import ncei.catalog.Application
import ncei.catalog.service.IndexAdminService
import ncei.catalog.service.Service
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll
import spock.util.concurrent.PollingConditions

import static org.hamcrest.Matchers.equalTo
import static org.hamcrest.Matchers.hasItems
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT

@SpringBootTest(classes = [Application], webEnvironment = RANDOM_PORT)
class IndexControllerApiSpec extends Specification {
  final String SEARCH_ENDPOINT = '/search'

  @Value('${local.server.port}')
  private String port

  @Value('${server.context-path:/}')
  private String contextPath

  @Autowired
  Service service
  @Autowired
  IndexAdminService indexAdminService

  private ContentType contentType = ContentType.JSON
  def poller = new PollingConditions(timeout: 5)

  @Shared
  Map metadata = [
      id        : '1',
      type      : "junk",
      attributes: [dataset: "testDataset", fileName: "testFileName1"]
  ]
  @Shared
  Map metadata2 = [
      id        : '2',
      type      : "junk",
      attributes: [dataset: "testDataset", fileName: "testFileName2"]
  ]

  def setup() {
    RestAssured.baseURI = "http://localhost"
    RestAssured.port = port as Integer
    RestAssured.basePath = contextPath

    service.INDEX = 'test_index'
    if (indexAdminService.indexExists('test_index')) {
      indexAdminService.deleteIndex('test_index')
    }
    indexAdminService.createIndex('test_index')
  }

  def 'Search with no search params'() {
    setup:
    service.upsert(metadata)
    service.upsert(metadata2)
    poller.eventually {
      assert service.search().data.size() == 2
    }

    expect: "You can hit the application search endpoint WITHOUT search params and get back the saved data"
    RestAssured.given()
        .contentType(contentType)
      .when()
        .get(SEARCH_ENDPOINT)
      .then()
        .assertThat()
        .statusCode(200)
        .body("data.size", equalTo(2))
        .body("data.id", hasItems(metadata.id, metadata2.id))
  }

  @Unroll
  def 'Search with search params #searchParams'() {
    setup:
    service.upsert(metadata)
    service.upsert(metadata2)
    poller.eventually {
      assert service.search().data.size() == 2
    }

    expect: "Search WITH search params and get back the expected data"
    RestAssured.given()
        .contentType(contentType)
        .params(searchParams)
      .when()
        .get(SEARCH_ENDPOINT)
      .then()
        .assertThat()
        .statusCode(200)
        .body("data.size", equalTo(expCount))
        .body(pathMatch, matcher)

    where:
    searchParams                                  | expCount | pathMatch                 | matcher
    [q: "dataset:${metadata.attributes.dataset}"] | 2        | "data.id"                 | hasItems(metadata.id, metadata2.id)
    [size: 1]                                     | 1        | "data.attributes.dataset" | hasItems(metadata.attributes.dataset)
  }

  def 'Search results from page 0 does not equal results from page 1'() {
    setup:
    service.upsert(metadata)
    service.upsert(metadata2)
    poller.eventually {
      assert service.search().data.size() == 2
    }

    when: "Get search results of page 0 then page 1"
    Response responsePage0 =
        RestAssured.given()
          .contentType(contentType)
          .params([size: 1, from: 0])
        .when()
          .get(SEARCH_ENDPOINT)
        .then()
          .assertThat()
          .statusCode(200)
          .extract().response()

    Response responsePage1 =
        RestAssured.given()
          .contentType(contentType)
          .params([size: 1, from: 1])
        .when()
          .get(SEARCH_ENDPOINT)
        .then()
          .assertThat()
          .statusCode(200)
          .extract().response()

    then: "Search the results should be different between the two different pages"
    responsePage0.as(Map) != responsePage1.as(Map)
  }

  def 'returns empty list when nothing has been indexed'() {

    expect:
    RestAssured.given()
        .contentType(contentType)
      .when()
        .get(SEARCH_ENDPOINT)
      .then()
        .assertThat()
        .statusCode(200)
        .body("data.size", equalTo(0))
  }
}
