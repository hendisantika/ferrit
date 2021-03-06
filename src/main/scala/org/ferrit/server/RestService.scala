package org.ferrit.server

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout
import play.api.libs.json.Json
import spray.can.server.Stats
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import spray.http.StatusCodes
import spray.httpx.unmarshalling._
import spray.httpx.marshalling._
import spray.httpx.PlayJsonSupport._
import spray.routing.{Directive1, HttpService, ValidationRejection}
import spray.util._ // to resolve "actorSystem"
import reflect.ClassTag // workaround, see below
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.ferrit.core.crawler.{CrawlerManager, CrawlConfig, CrawlRejectException}
import org.ferrit.core.crawler.CrawlConfigTester
import org.ferrit.core.crawler.CrawlConfigTester.{Results, Result}
import org.ferrit.core.crawler.CrawlerManager.{StartJob, JobStartFailed}
import org.ferrit.core.crawler.CrawlerManager.{StopJob, StopAllJobs, StopAccepted, JobNotFound}
import org.ferrit.core.crawler.CrawlerManager.{JobsQuery, JobsInfo}
import org.ferrit.core.model.{Crawler, CrawlJob, DocumentMetaData, FetchLogEntry}
import org.ferrit.core.json.PlayJsonImplicits._
import org.ferrit.core.uri.CrawlUri
import org.ferrit.dao.{DAOFactory, CrawlJobDAO, CrawlerDAO, DocumentMetaDataDAO, FetchLogEntryDAO, Journal}
import org.ferrit.server.json.{Id, Message, ErrorMessage}
import org.ferrit.server.json.PlayJsonImplicits._

/**
 * Makes the crawler service available as a REST API.
 */
class RestService(

    override val ferrit: ActorRef, 
    val daoFactory: DAOFactory, 
    override val crawlerManager: ActorRef,
    override val logger: ActorRef

  ) extends Actor with RestServiceRoutes {

  def actorRefFactory = context
  def receive: Receive = runRoute(routes) // runRoute wrapper
  override def createJournal = context.actorOf(Props(classOf[Journal], daoFactory))

  override val fleDao: FetchLogEntryDAO = daoFactory.fetchLogEntryDao
  override val crawlJobDao: CrawlJobDAO = daoFactory.crawlJobDao
  override val crawlerDao: CrawlerDAO = daoFactory.crawlerDao
  override val docMetaDao: DocumentMetaDataDAO = daoFactory.documentMetaDataDao

}

trait RestServiceRoutes extends HttpService {

  import RestServiceRoutes._

  implicit def executionContext = actorRefFactory.dispatcher

  val ferrit: ActorRef
  val crawlerManager: ActorRef
  val logger: ActorRef
  def createJournal: ActorRef

  val fleDao: FetchLogEntryDAO
  val crawlJobDao: CrawlJobDAO
  val crawlerDao: CrawlerDAO
  val docMetaDao: DocumentMetaDataDAO
  
  // General ask timeout
  val askTimeout = new Timeout(3.seconds)

  // Provide generous Timeout when starting a job. Seeds need enqueing
  // which in turn requires fetching robots.txt to be sure they are valid.
  val startJobTimeout = new Timeout(30.seconds)

  val shutdownDelay = 1.second
  val webDirectory = "web"

  implicit val customRejectionHandler = CustomRejectionHandler.customRejectionHandler
  implicit def customExceptionHandler(implicit log: LoggingContext) = CustomExceptionHandler.handler(log)

  val routes = {
    pathSingleSlash {
      getFromResource(s"$webDirectory/index.html")
    } ~
    path("crawlers" / Segment / "jobs" / Segment / "fetches") { (crawlerId, jobId) =>
      get {
        withCrawler(crawlerId) { crawler =>
          withCrawlJob(crawlerId, jobId) { job =>
            complete {
              fleDao.find(jobId)
            }
          }
        }
      }
    } ~
    path("crawlers" / Segment / "jobs" / Segment / "assets") { (crawlerId, jobId) =>
      get {
        withCrawler(crawlerId) { crawler =>
          withCrawlJob(crawlerId, jobId) { job =>
            complete {
              docMetaDao.find(jobId)
            }
          }
        }  
      }
    } ~
    path("crawlers" / Segment / "jobs" / Segment) { (crawlerId, jobId) =>
      get {
        withCrawler(crawlerId) { crawler =>
          withCrawlJob(crawlerId, jobId) { job =>
            complete(job)
          }
        }  
      }
    } ~
    path("crawlers" / Segment / "jobs") { crawlerId =>
      get {
        withCrawler(crawlerId) { crawler =>
          complete(crawlJobDao.find(crawlerId))
        }  
      }
    } ~
    path("crawlers" / Segment) { crawlerId =>
      get {
        withCrawler(crawlerId) { crawler =>
          complete(crawler.config)
        }
      } ~
      put {
        entity(as[CrawlConfig]) { config =>
          withCrawler(crawlerId) { crawler =>  
            complete {
              val results: CrawlConfigTester.Results = CrawlConfigTester.testConfig(config)
              if (results.allPassed) {  
                  val config2 = config.copy(id = crawlerId)
                  val crawler = Crawler(crawlerId, config2)
                  crawlerDao.insert(crawler)
                  StatusCodes.Created -> config2
              } else {
                StatusCodes.BadRequest -> results
              }
            }
          }
        }
      } ~
      delete {
        withCrawler(crawlerId) { crawler =>
          complete {
            crawlerDao.delete(crawlerId)
            StatusCodes.NoContent
          }
        }
      }
    } ~
    path("crawlers") {
      get {
        complete {
          crawlerDao.findAll().map(crawler => crawler.config)
        }
      } ~
      post {
        entity(as[CrawlConfig]) { config: CrawlConfig =>
          complete {
            val results: CrawlConfigTester.Results = CrawlConfigTester.testConfig(config)
            if (results.allPassed) {
                val crawler = Crawler.create(config)
                crawlerDao.insert(crawler)
                StatusCodes.Created -> crawler.config 
            } else {
              StatusCodes.BadRequest -> results
            }
          }
        }
      }
    } ~
    path("crawl-config-test") { 
      post {
        entity(as[CrawlConfig]) { config =>
          complete {
            val results: CrawlConfigTester.Results = CrawlConfigTester.testConfig(config)
            val sc = if (results.allPassed) StatusCodes.OK else StatusCodes.BadRequest
            sc -> results
          }
        }  
      }
    } ~
    path("jobs") {
      get {
        parameter("date" ? DateParamDefault) { dateParam =>
          makeDateKey(dateParam) match {
            case Success(dateKey) => complete(crawlJobDao.find(dateKey))
            case Failure(t) => reject(BadParamRejection("date", dateParam))                
          }
        }
      }
    } ~
    path("job-processes") {
      post {
        entity(as[Id]) { crawlerId =>
          withCrawler(crawlerId.id) { crawler =>
            complete {
              crawlerManager
                .ask(StartJob(crawler.config, Seq(logger, createJournal)))(startJobTimeout)
                .mapTo[CrawlJob]
                .map({job => StatusCodes.Created -> job})
            }
          }
        }
      } ~
      get {
        complete {
          crawlerManager
            .ask(JobsQuery())(askTimeout)
            .mapTo[JobsInfo]
            .map({jobsInfo => jobsInfo.jobs})
        }
      } ~
      delete {
        complete {
          crawlerManager
            .ask(StopAllJobs())(askTimeout)
            .mapTo[StopAccepted]
            .map(sa => StatusCodes.Accepted ->  Message(StopAllJobsAcceptedMsg.format(sa.ids.size)))
        }
      }
    
    } ~
    path("job-processes" / Segment) { jobId =>
      delete {
        onSuccess((crawlerManager ? StopJob(jobId))(askTimeout)) {
          case StopAccepted(Seq(id)) => complete(StatusCodes.Accepted -> Message(StopJobAcceptedMsg.format(id)))
          case JobNotFound => reject(BadEntityRejection("crawl job", jobId))
        }
      }
    } ~
    path("shutdown") {
      post {
        complete {
          actorSystem.scheduler.scheduleOnce(shutdownDelay) {
            ferrit ! Ferrit.Shutdown
          }
          Message(ShutdownReceivedMsg)
        }  
      }
    }
  }

 
  def withCrawler(crawlerId: String):Directive1[Crawler] = {
    crawlerDao.find(crawlerId) match {
      case None => reject(BadEntityRejection("crawler", crawlerId))
      case Some(crawler) => provide(crawler)
    }   
  }

  def withCrawlJob(crawlerId: String, crawlJobId: String):Directive1[CrawlJob] = {
    crawlJobDao.find(crawlerId, crawlJobId) match {
      case None => reject(BadEntityRejection("crawl job", crawlJobId))
      case Some(crawlJob) => provide(crawlJob)
    }
  }

}

object RestServiceRoutes {
 
  val DateParamDefault = "no-date"
  val DateParamFormat = "YYYY-MM-dd" 
  val NoPostToNamedCrawlerMsg = "Cannot post to an existing crawler resource"
  val StopJobAcceptedMsg = "Stop request accepted for job [%s]"
  val StopAllJobsAcceptedMsg = "Stop request accepted for %s jobs"
  val ShutdownReceivedMsg = "Shutdown request received"

  def makeDateKey(dateParam: String):Try[DateTime] =
    try {
      val dateKey = (if (DateParamDefault == dateParam) {
        new DateTime
      } else {
        DateTimeFormat.forPattern(DateParamFormat).parseDateTime(dateParam)
      }).withTimeAtStartOfDay
      Success(dateKey)
    } catch {
      case e: IllegalArgumentException => Failure(e)
    }

}