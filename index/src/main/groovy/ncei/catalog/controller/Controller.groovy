package ncei.catalog.controller

import groovy.util.logging.Slf4j
import ncei.catalog.service.Service
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController

import javax.servlet.http.HttpServletResponse

import static org.springframework.web.bind.annotation.RequestMethod.GET

@Slf4j
@RestController
@RequestMapping(value = '/')
class Controller {

  @Autowired
  Service service

  @RequestMapping(value = "/search", method = [GET])
  @ResponseBody
  Map search(@RequestParam Map params, HttpServletResponse response) {
    try {
      log.info "Received search request parameter=$params"
      service.search(params)
    } catch (e) {
      String message = "Failed to query with params=$params Exception=$e"
      log.error (message, e)
      response.status = response.SC_INTERNAL_SERVER_ERROR
      [message: message]
    }
  }
}
