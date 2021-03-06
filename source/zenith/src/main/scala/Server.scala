/**
 *   __________            .__  __  .__
 *   \____    /____   ____ |__|/  |_|  |__
 *     /     // __ \ /    \|  \   __\  |  \
 *    /     /\  ___/|   |  \  ||  | |   Y  \
 *   /_______ \___  >___|  /__||__| |___|  /
 *           \/   \/     \/              \/
 */
package zenith.server

import zenith._
import zenith.Extensions._
import scala.util.{Try, Success, Failure}
import cats._
import cats.data._
import org.joda.time.{DateTimeZone, DateTime}
import scala.io.Source
import cats.Monad.ops._
import java.lang.reflect.{Method => ReflectedMethod}


/** DATA */
/**********************************************************************************************************************/

final case class EndpointDocumentation (id: String, description: Option[String], method: HttpMethod, path: String)
object EndpointDocumentation { import io.circe._, io.circe.generic.semiauto._
  implicit val jeHttpMethod: Encoder[HttpMethod] = new Encoder[HttpMethod] { def apply (hm: HttpMethod): Json = { Json.string (hm.toString) } }
  implicit val je = deriveFor[EndpointDocumentation].encoder
}

final case class ServiceDocumentation (id: String, description: Option[String], endpoints: List[EndpointDocumentation])
object ServiceDocumentation { import io.circe.generic.semiauto._
  implicit val je = deriveFor[ServiceDocumentation].encoder
}

final case class ServerDocumentation (id: String, services: List[ServiceDocumentation])
object ServerDocumentation { import io.circe.generic.semiauto._
  implicit val je = deriveFor[ServerDocumentation].encoder
}

final case class HttpContent (format: ContentType, data: String)

abstract class HttpServerConfig[Z[_]: Context] {
  val identifier: String
  val port: Int
  val serviceGroups: List[HttpServiceGroup[Z]]

  val resourcePaths: List[String] = Nil
  val index: List[String] = "index.html" :: "index.htm" :: Nil
  val documentationPlugin: Boolean = true

  def contextHandler (a: Z[HttpResponse]): Z[HttpResponse] = Async[Z].transform (a) {
    case Failure (ex) => HttpResponse.plain (500, ex.getMessage)
    case Success (ok) => ok
  }
}

final case class HttpServiceGroup[Z[_]: Context] (
  services: List[HttpService[Z]],
  requestFilters: List[RequestFilter[Z]] = Nil,
  responseMappers: List[ResponseMapper[Z]] = Nil)


/** FILTERS */
/**********************************************************************************************************************/

/**
 * A request filter takes a request and does one of the following:
 * - returns a response, signalling the end of processing for this request, stopping it ever reaching a handler.
 * - if everything is fine the filter returns the request, possibly with modifications (like adding additional headers)
 */
abstract class RequestFilter[Z[_]: Context] (val name: String) {
  def run (httpRequest: HttpRequest): Z[HttpResponse Either HttpRequest]
}

/**
 * ResponseMapper
 */
abstract class ResponseMapper[Z[_]: Context] (val name: String) {
  def run (httpResponse: HttpResponse): Z[HttpResponse]
}


/** ENDPOINT */
/**********************************************************************************************************************/

/**
 * HttpServiceEndpoint
 */
final case class HttpServiceEndpoint[Z[_]: Context] (parent: HttpService[Z], fn: ReflectedMethod)(implicit logger: Logger[Z]) {
  val id = fn.getName.splitCamelCase
  val annotations = fn.getAnnotations

  val description = annotations
    .filter (x => x.annotationType == classOf[description])
    .map (x => x.asInstanceOf[description].value)
    .headOption

  val method = annotations
    .filter (x => x.annotationType == classOf[method])
    .map (x => x.asInstanceOf[method].value)
    .headOption
    .getOrElse (HttpMethod.GET)

  val path = {
    val resourcePathOpt = parent
      .getClass
      .getAnnotations
      .filter (x => x.annotationType == classOf[path])
      .map (x => x.asInstanceOf[path].value)
      .headOption

    val endpointPathOpt = annotations
      .filter (x => x.annotationType == classOf[path])
      .map (x => x.asInstanceOf[path].value)
      .headOption

    (resourcePathOpt, endpointPathOpt) match {
      case (Some (resourcePath), Some (endpointPath)) => resourcePath + endpointPath
      case (Some (resourcePath), None) => resourcePath
      case (None, Some (endpointPath)) => endpointPath
      case _ => throw new Exception ("Endpoint the parent resource and/or the endpoint itself must be tagged with the `Path` annotation.")
    }
  }

  def docs: EndpointDocumentation = EndpointDocumentation (id, description, method, path)

  def handler = new PartialFunction[HttpRequest, Z[HttpResponse]] {
    private def shouldHandleRequest (request: HttpRequest): Boolean = path == request.path && method.toString == request.method
    def isDefinedAt (request: HttpRequest) = shouldHandleRequest (request)
    def apply (request: HttpRequest): Z[HttpResponse] = {
      val x = fn.invoke (parent, request)
      val a = Try { x.asInstanceOf[Z[HttpResponse]] }.toOption
      for {
        _ <- logger.debug (s"executing handler for ${request.path}")
        result <- a match {
          case Some (z) => z
          case _ => throw new Exception ("Endpoint signature not supported.")
        }
      } yield result
    }
  }
}


/** SERVICE */
/**********************************************************************************************************************/

/**
 * A Http Service can be thought of as a pipeline.
 */
abstract class HttpService[Z[_]: Context] {
  final val id = super.getClass.getSimpleName.splitCamelCase
  final val description = super
    .getClass
    .getAnnotations
    .filter (x => x.annotationType == classOf[description])
    .map (x => x.asInstanceOf[description].value)
    .headOption

  def requestPipeline: List[RequestFilter[Z]] = Nil
  def responsePipeline: List[ResponseMapper[Z]] = Nil

  final def handler: PartialFunction[HttpRequest, Z[HttpResponse]] = endpoints.map (_.handler).reduceLeft (_ orElse _)
  final def docs: ServiceDocumentation = ServiceDocumentation (id, description, endpoints.map (_.docs))

  private def isEndpoint (fn: ReflectedMethod): Boolean = Option (fn.getAnnotation (classOf[endpoint])).isDefined

  private val endpoints: List[HttpServiceEndpoint[Z]] = super
    .getClass
    .getMethods
    .collect { case fn if isEndpoint (fn) => new HttpServiceEndpoint[Z] (this, fn) }
    .toList
}


/** SERVER */
/**********************************************************************************************************************/

/**
 * HttpServerProvider
 */
abstract class HttpServerProvider[Z[_]: Context] {
  def create (config: HttpServerConfig[Z]): HttpServer[Z]
  def getServer (): Option[HttpServer[Z]]
  def destroy (): Unit
}

/**
 * HttpServer
 */
final case class HttpServer[Z[_]: Context](config: HttpServerConfig[Z])(implicit async: Async[Z], logger: Logger[Z]) {
  import logger._, async._

  val name = config.identifier
  val port = config.port
  val endpointGroups = config.documentationPlugin match {
    case true => config.serviceGroups :+ HttpServiceGroup[Z] (new DocumentationService[Z] (docs) :: Nil)
    case false => config.serviceGroups
  }

  def docs (): ServerDocumentation = ServerDocumentation (config.identifier, endpointGroups.flatMap (_.services).map (_.docs))

  def process (request: HttpRequest): Z[HttpResponse] = {
    val z = processZ (request); config.contextHandler (z); z
  }

  def processZ (request: HttpRequest): Z[HttpResponse] = {
    val startTime = DateTime.now (DateTimeZone.UTC).getMillis
    for {
      _ <- debug (s"Received request:\n${request.toPrettyString}")
      reqPath = request.path
      _ <- debug (s"Looking for handler for ${request.method} $reqPath")
      result <- endpointGroups
        .map (x => (x, x.services.collectFirst { case s if s.handler.isDefinedAt (request) => s }))
        .collectFirst { case (e, s) if s.isDefined => (e, s.get) } match {
        case None => for {
          result <- tryStaticResource (reqPath)
          _ <- debug (s"No suitable handler found.")
        } yield result
        case Some ((endpointGroup, service)) => for {
          _ <- debug (s"Found handler in service: ${service.id}")
          filteredRequest <- applyRequestFilters (request, endpointGroup.requestFilters)
          response <- filteredRequest match {
            case Left (bad) => for {
              _ <- debug (s"Not handling request, filters returned: $bad")
              v <- success (bad)
            } yield v
            case Right (good) => for {
              _ <- debug (s"About to handle request: $good")
              result <- service.handler.apply (good)
            } yield result
          }
          mappedResponse <- applyResponseMappers (response, endpointGroup.responseMappers)
          _ <- debug (s"Generated response:\n${response.toPrettyString}")
          processingTime = DateTime.now (DateTimeZone.UTC).getMillis - startTime
          _ <- debug (s"Processing time: ${processingTime}ms")
        } yield mappedResponse
      }
    } yield result
  }

  private def applyRequestFilters (req: HttpRequest, requestFilters: List[RequestFilter[Z]]): Z[HttpResponse Either HttpRequest] = for {
    _ <- debug (s"About to apply request filters")
    result <- requestFilters.foldLeft (success[HttpResponse Either HttpRequest] (Right (req))) { (acc, f) =>
      for {
        _ <- debug (s"Sequencing request filter:${f.name}")
        a <- acc
        result <- a match {
          case Left (bad) => success[HttpResponse Either HttpRequest] (Left (bad))
          case Right (good) => f.run (good)
        }
      } yield result
    }
  } yield result

  private def applyResponseMappers (resp: HttpResponse, responseMappers: List[ResponseMapper[Z]]): Z[HttpResponse] = for {
    _ <- debug (s"About to apply response mappers")
    result <- responseMappers.foldLeft (success (resp)) { (acc, f) =>
      for {
        _ <- debug (s"Sequencing response mapper:${f.name}")
        a <- acc
        result <- f.run (a)
      } yield result
    }
  } yield result

  private def getBestPath[T[_]: Context] (requestPath: String) = ReaderT[Id, HttpServerConfig[T], Option[String]] { (config: HttpServerConfig[T]) =>
    config.resourcePaths match {
      case Nil => None: Option[String]
      case staticsPath :: tail =>
        val reqPath = config.index.foldRight (requestPath) { (a, i) =>
          ResourceUtils.resourceExists (staticsPath + "/" + i) match {
            case false => a
            case true => "/" + i
          }
        }
        Option (staticsPath + reqPath)
    }
  }

  private def tryStaticResource (requestPath: String): Z[HttpResponse] = for {
    _ <- debug (s"Working out best path.")
    bestPathOpt = getBestPath[Z] (requestPath).run (config)
    result <- bestPathOpt match {
      case None => for {
        _ <- debug (s"No static resource path configured.")
        result <- success[Option[HttpResponse]] (None)
      } yield result
      case Some (bestPath) => for {
        _ <- debug (s"Looking for static resource: $bestPath")
        result <- ResourceUtils.resourceExists (bestPath) match {
          case false => for {
            _ <- debug (s"No suitable static resource found for: $requestPath")
          } yield None: Option[HttpResponse]
          case true =>
            val resource = getClass.getResource (bestPath)
            for {
              _ <- debug (s"Found static resource at: ${resource.getPath}")
              content = Source.fromFile (resource.getFile).getLines ().mkString ("\n")
            } yield content match {
              case null | "" => Some (HttpResponse.plain (404, "Not Found"))
              case body =>
                val contentType = ResourceUtils.guessContentTypeFromPath (bestPath)
                Option (HttpResponse (200, Some (body), Map ("Content-Type" -> contentType)))
            }
        }
      } yield result
    }
  } yield result match {
    case Some (response) => response
    case None => HttpResponse (404)
  }
}


/** DOCUMENTATION SERVICE */
/**********************************************************************************************************************/

final class DocumentationService[Z[_]: Context] (docs: () => ServerDocumentation) extends HttpService[Z] {
  @endpoint
  @path ("/documentation")
  @method (HttpMethod.GET)
  @description ("Provides documentation for the API.")
  def docs (request: HttpRequest): Z[HttpResponse] = {
    import io.circe.syntax._
    Async[Z].success (HttpResponse.json (200, docs ().asJson.noSpaces))
  }
}