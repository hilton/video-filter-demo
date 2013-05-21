package controllers

import play.api._
import play.api.mvc._
import play.api.libs.concurrent.Promise
import play.api.libs.iteratee.{Concurrent, Enumerator, Iteratee}
import scala.collection.mutable.{SynchronizedQueue, HashMap}
import java.util.concurrent.atomic.{AtomicReference, AtomicLong}
import scala.concurrent.ExecutionContext.Implicits.global
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import Concurrent._

import java.io._

import play.api._
import play.api.mvc._
import play.api.http.HeaderNames._
import play.api.Play.current
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import Concurrent._
import play.api.libs.json._
import play.api.libs.json.Json._
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import org.jdesktop.swingx.graphics.ReflectionRenderer
import org.jdesktop.swingx.util.GraphicsUtilities

object Application extends Controller {

  // Home page
  def index = Action {  implicit request =>
    Ok(views.html.index())
  }

  // Entry point for our client
  def show = Action {  implicit request =>
    Ok(views.html.show())
  }

  // This is the image stream coming from the web cam. It is what we call an Enumerator. It
  // represents a stream that is pushing chunks of data of type E. An Enumerator[String] is a stream of strings.
  // Enumerators can be composed one after the other, or interleaved concurrently providing means of streams management.
  val (rawStream, channel) = Concurrent.broadcast[Array[Byte]]

  // Allow to perform transformation
  val video = new ImageEncoder

  // Enumeratees: an adapter from a stream of Froms to a stream of Tos. Note that an Enumeratee can rechunk differently,
  // add or remove chunks or parts of them. Enumeratees as we will see are instrumental for stream manipulation.
  // Here we are enumeratee that transform the image stream
  val videoBlueEncoder = Enumeratee.map[Array[Byte]](video.color(_, Color.blue))
  val videoPinkEncoder = Enumeratee.map[Array[Byte]](video.color(_, Color.pink))
  val videoReflectionEncoder = Enumeratee.map[Array[Byte]](video.appendReflection)
  val videoShadowEncoder = Enumeratee.map[Array[Byte]](video.appendShadow)

  // We apply transformation to our original image stream coming from the web cam
  val videoStream = rawStream &> videoReflectionEncoder  ><> videoPinkEncoder // ><> videoBlueEncoder ><> videoPinkEncoder

  // This is the ws method called from our main page
  def acquire = WebSocket.using[Array[Byte]] {   implicit request =>
    // The Iteratee is coming from the camera. It is an immutable interface that represents a consumer, it consumes chunks of data each of type Byte[Array]
    // and eventually produces a computed value of type A. Iteratee[String,Int] is an iteratee that consumes chunks of strings and eventually
    // produces an Int (that could be for instance number of characters in the passed chunks)
    ( Iteratee.foreach[Array[Byte]] {
      case message : Array[Byte] => {
        // Push the message to the enumerator (rawstream)
        channel.push( message )
      }
    }.map { _ =>
      channel.eofAndEnd()
    },  videoStream)
  }

  // This is the ws method called by our clients
  def stream =  WebSocket.using[Array[Byte]] {  implicit request =>
    (Iteratee.ignore , videoStream)
  }


  // Class that transform an image into another image
  case class ImageEncoder () {

    val renderer = new ReflectionRenderer
    renderer.setOpacity(0.5f)
    renderer.setLength(0.3f)
    renderer.setBlurEnabled(true)

    val shadow = new org.jdesktop.swingx.graphics.ShadowRenderer
    shadow.setOpacity(0.4f)



    def color(data: Array[Byte], color: Color): Array[Byte] = {
      val filter = new org.jdesktop.swingx.image.ColorTintFilter(color, 0.4f)
      val stream = new ByteArrayInputStream(data)
      val image = ImageIO.read(stream)
      val dst = new BufferedImage(image.getWidth, image.getHeight, image.getType)
      filter.filter(image, dst)


      val baos = new ByteArrayOutputStream()
      ImageIO.write( dst, "png", baos )
      baos.flush()

      baos.toByteArray
    }

    def appendReflection(data: Array[Byte]): Array[Byte] = {
        val stream = new ByteArrayInputStream(data)
        val image = ImageIO.read(stream)

      val reflection = renderer.appendReflection(image)


      val baos = new ByteArrayOutputStream()
      ImageIO.write( reflection, "png", baos )
      baos.flush()

      baos.toByteArray

    }


    def appendShadow(data: Array[Byte]): Array[Byte] = {
      val stream = new ByteArrayInputStream(data)
      val image = ImageIO.read(stream)

      val reflection = shadow.createShadow(image)


      val baos = new ByteArrayOutputStream()
      ImageIO.write( reflection, "png", baos )
      baos.flush()

      baos.toByteArray
    }

  }




}