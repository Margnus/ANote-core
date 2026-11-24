package dev.notecore.canvas.prototype
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureNanoTime
object CostProbe {
 @JvmStatic fun main(args:Array<String>) {
  val bytes=File(args[0]).readBytes(); val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  check(String(bytes.copyOfRange(0,8))=="NCSCALE1")
  val count=b.getInt(8);val points=b.getInt(12)
  fun stroke(index:Int,n:Int=points):StrokeSamples {
   b.position(16+index*points*28)
   val x=DoubleArray(n);val y=DoubleArray(n);val p=FloatArray(n);val t=LongArray(n)
   repeat(n){x[it]=b.double;y[it]=b.double;p[it]=b.float;t[it]=b.long}
   return StrokeSamples.copyOf(x,y,p,t)
  }
  if(count==10000) for(kind in BrushKind.entries) {
   var vertices=0L;var sumBytes=0L;var peak=0L;var nanos=0L;var rejected=0
   repeat(count){i->val s=stroke(i);var result:SweptDiscBuild?=null
    nanos+=measureNanoTime {result=SweptDiscGeometryPoc.build(s,BrushSpec(kind,1,10.0,-1))}
    when(val r=result){is SweptDiscBuild.Ready->{vertices+=r.geometry.vertexCount;sumBytes+=r.geometry.logicalByteCharge;peak=maxOf(peak,r.geometry.logicalByteCharge)};else->rejected++}
   }
   println("{\"case\":\"S2\",\"kind\":\"$kind\",\"points\":${count*points},\"rejected\":$rejected,\"vertices\":$vertices,\"sum_output_logical_bytes\":$sumBytes,\"peak_single_output_logical_bytes\":$peak,\"build_ns\":$nanos}")
  } else {
   for(n in listOf(100,1000,5000,10000,20000,100000)) for(scale in listOf(1.0,4.0)) {
    val s=stroke(0,n);var r:SweptDiscBuild?=null
    val ns=measureNanoTime {r=SweptDiscGeometryPoc.build(s,BrushSpec(BrushKind.MONOLINE,1,10.0,-1),Affine2D(a=scale,d=scale))}
    val detail=when(val x=r){is SweptDiscBuild.Ready->"ready:${x.geometry.vertexCount}:${x.geometry.logicalByteCharge}";is SweptDiscBuild.Rejected->x.reason.name;else->"missing"}
    println("{\"case\":\"S9-prefix\",\"points\":$n,\"scale\":$scale,\"result\":\"$detail\",\"build_ns\":$ns}")
   }
  }
  val brush=BrushSpec(BrushKind.PRESSURE,1,10.0,-1)
  fun prefix(n:Int)=StrokeSamples.copyOf(DoubleArray(n){it.toDouble()},DoubleArray(n),FloatArray(n){if(it==2)1f else 0f},LongArray(n){it.toLong()})
  val before=SweptDiscGeometryPoc.build(prefix(2),brush) as SweptDiscBuild.Ready
  val after=SweptDiscGeometryPoc.build(prefix(3),brush) as SweptDiscBuild.Ready
  check(before.discVertices!=after.discVertices)
  check(before.geometry.x(1)!=after.geometry.x(1))
  println("{\"case\":\"late-pressure-prefix\",\"before_sides\":${before.discVertices},\"after_sides\":${after.discVertices},\"earlier_geometry_changed\":true}")
 }
}
