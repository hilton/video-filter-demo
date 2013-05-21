package controllers

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import scala.concurrent.ExecutionContext.Implicits.global

import org.jdesktop.swingx.graphics.ReflectionRenderer

import javax.imageio.ImageIO
import play.api.libs.iteratee.Concurrent
import play.api.libs.iteratee.Enumeratee
import play.api.libs.iteratee.Iteratee
import play.api.mvc.Action
import play.api.mvc.Controller
import play.api.mvc.WebSocket

object Application extends Controller {

  // Home page
  def index = Action { implicit request =>
    Ok(views.html.index())
  }

  // Entry point for our client
  def show = Action { implicit request =>
    Ok(views.html.show())
  }

  // This is the image stream coming from the web cam. It is what we call an Enumerator. It
  // represents a stream that is pushing chunks of data of type E. An Enumerator[String] is a stream of strings.
  // Enumerators can be composed one after the other, or interleaved concurrently providing means of streams management.
  val (rawStream, channel) = Concurrent.broadcast[Array[Byte]]

  // Allow to perform transformation
  val transformer = new ImageTransformer

  // Enumeratees: an adapter from a stream of Froms to a stream of Tos. Note that an Enumeratee can rechunk differently,
  // add or remove chunks or parts of them. Enumeratees as we will see are instrumental for stream manipulation.
  // Here we are enumeratee that transform the image stream
  val readImage = Enumeratee.map[Array[Byte]](transformer.readImage)
  val writeImage = Enumeratee.map[BufferedImage](transformer.writeImage)
  val videoBlueEncoder = Enumeratee.map[BufferedImage](transformer.color(_, Color.blue))
  val videoPinkEncoder = Enumeratee.map[BufferedImage](transformer.color(_, Color.pink))
  val videoReflectionEncoder = Enumeratee.map[BufferedImage](transformer.appendReflection)
  val videoShadowEncoder = Enumeratee.map[BufferedImage](transformer.appendShadow)

  // We apply transformation to our original image stream coming from the web cam
  val videoStream = rawStream &> readImage ><> videoReflectionEncoder ><> videoPinkEncoder ><> writeImage

  // This is the ws method called from our main page
  def acquire = WebSocket.using[Array[Byte]] { implicit request =>
    // The Iteratee is coming from the camera. It is an immutable interface that represents a consumer, it consumes chunks of data each of type Byte[Array]
    // and eventually produces a computed value of type A. Iteratee[String,Int] is an iteratee that consumes chunks of strings and eventually
    // produces an Int (that could be for instance number of characters in the passed chunks)
    val iteratee = Iteratee.foreach[Array[Byte]] {
      case message: Array[Byte] => {
        // Push the message to the enumerator (rawstream)
        channel.push(message)
      }
    }.map { _ =>
      channel.eofAndEnd()
    }

    (iteratee, videoStream)
  }

  // This is the ws method called by our clients
  def stream = WebSocket.using[Array[Byte]] { implicit request =>
    (Iteratee.ignore, videoStream)
  }

  // Class that transform an image into another image
  class ImageTransformer {

    val renderer = new ReflectionRenderer
    renderer.setOpacity(0.5f)
    renderer.setLength(0.3f)
    renderer.setBlurEnabled(true)

    val shadow = new org.jdesktop.swingx.graphics.ShadowRenderer
    shadow.setOpacity(0.4f)

    def readImage(byteArray: Array[Byte]) = {
      val stream = new ByteArrayInputStream(byteArray)
      val image = ImageIO.read(stream)
      image
    }

    def writeImage(image: BufferedImage) = {
      val baos = new ByteArrayOutputStream()
      ImageIO.write(image, "png", baos)
      baos.flush()
      baos.toByteArray
    }

    def color(image: BufferedImage, color: Color): BufferedImage = {
      val filter = new org.jdesktop.swingx.image.ColorTintFilter(color, 0.4f)
      val dst = new BufferedImage(image.getWidth, image.getHeight, image.getType)
      filter.filter(image, dst)
      dst
    }

    def appendReflection(image: BufferedImage): BufferedImage =
      renderer.appendReflection(image)

    def appendShadow(image: BufferedImage): BufferedImage =
      shadow.createShadow(image)

  }

}