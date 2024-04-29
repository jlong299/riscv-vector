package darecreek.vfuAutotest.fullPipeline

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3._
import chiseltest.WriteVcdAnnotation
import scala.reflect.io.File
import scala.reflect.runtime.universe._
import scala.collection.mutable.Map
import darecreek.exu.vfu._
import darecreek.exu.vfu.mac._
import darecreek.exu.vfu.VInstructions._
import chipsalliance.rocketchip.config.Parameters

class VmaccvvTestBehavior extends VmMacTestBehavior("vmacc.vv.data", ctrlBundles.vmacc_vv, "-", "vmacc_vv", true) {}
class VnmsacvvTestBehavior extends VmMacTestBehavior("vnmsac.vv.data", ctrlBundles.vnmsac_vv, "-", "vnmsac_vv", true) {}
class VmaddvvTestBehavior extends VmMacTestBehavior("vmadd.vv.data", ctrlBundles.vmadd_vv, "-", "vmadd_vv", false) {}
class VnmsubvvTestBehavior extends VmMacTestBehavior("vnmsub.vv.data", ctrlBundles.vnmsub_vv, "-", "vnmsub_vv", false) {}

class VmaccvxTestBehavior extends VmMacTestBehavior("vmacc.vx.data", ctrlBundles.vmacc_vx, "-", "vmacc_vx", true) {}
class VnmsacvxTestBehavior extends VmMacTestBehavior("vnmsac.vx.data", ctrlBundles.vnmsac_vx, "-", "vnmsac_vx", true) {}
class VmaddvxTestBehavior extends VmMacTestBehavior("vmadd.vx.data", ctrlBundles.vmadd_vx, "-", "vmadd_vx", false) {}
class VnmsubvxTestBehavior extends VmMacTestBehavior("vnmsub.vx.data", ctrlBundles.vnmsub_vx, "-", "vnmsub_vx", false) {}

class VmMacTestBehavior(fn : String, cb : CtrlBundle, s : String, instid : String, accsac : Boolean) extends TestBehavior(fn, cb, s, instid) {
    
    override def isOrdered() : Boolean = false
    override def getTargetTestEngine() = TestEngine.MAC_TEST_ENGINE

    override def _getNextTestCase(simi:Map[String,String]) : TestCase = {
        val vs2data = UtilFuncs.multilmuldatahandle(simi.get("VS2").get)

        var vx = simi.get("RS1") != None || simi.get("FS1") != None
        var vv = simi.get("VS1") != None
        var vs1data : Array[String] = Array()
        if (vv)
            vs1data = UtilFuncs.multilmuldatahandle(simi.get("VS1").get)
        if (vx) {
            if (simi.get("RS1") != None)
                vs1data = UtilFuncs.multilmuldatahandle(simi.get("RS1").get)
            if (simi.get("FS1") != None) {
                vs1data = UtilFuncs.multilmuldatahandle(simi.get("FS1").get)
                vs1data(0) = s"h${vs1data(0).slice(17, 33)}"
            }
        }

        val oldvddata = UtilFuncs.multilmuldatahandle(simi.get("OLD_VD").get)
        val mask = UtilFuncs.multilmuldatahandle(simi.get("MASK").get)
        val vflmul = simi.get("vflmul").get
        val vxsat = simi.get("vxsat").get.toInt == 1
        val expectvd = UtilFuncs.multilmuldatahandle(simi.get("VD").get)
        val vxrm = simi.get("vxrm").get.toInt
        // println("lmel > 1, id", i)

        val vsew = UtilFuncs.vsewconvert(simi.get("vsew").get)

        var n_inputs = 1
        if(vflmul == "2.000000") n_inputs = 2
        if(vflmul == "4.000000") n_inputs = 4
        if(vflmul == "8.000000") n_inputs = 8
        
        // var finalVxsat = false
        var vd : BigInt = 0
        var vdres = false
            
        // println("1111")
        val resultChecker = ALUResultChecker.newGeneralVChecker(n_inputs, expectvd, 
            (a, b) => this.dump(simi, a, b))

        var srcBundles : Seq[SrcBundle] = Seq()
        var ctrlBundles : Seq[CtrlBundle] = Seq()

        for(j <- 0 until n_inputs){
            
            var srcBundle = SrcBundle(
                    vs2=vs2data(n_inputs - 1 - j), 
                    // vs1=vs1data(n_inputs - 1 - j),
                    old_vd=oldvddata(n_inputs - 1 - j),
                    mask=mask(0)
                )
            if (vv)
                srcBundle.vs1=vs1data(n_inputs - 1 - j)
            if (vx)
                srcBundle.rs1=vs1data(0)

            val ctrlBundle = ctrl.copy(
                vsew=vsew,
                vl=simi.get("vl").get.toInt,
                vlmul = UtilFuncs.lmulconvert(vflmul).toInt, 
                ma = (simi.get("ma").get.toInt == 1),
                ta = (simi.get("ta").get.toInt == 1),
                vm = (simi.get("vm").get.toInt == 1),
                uopIdx=j,
                vxrm = vxrm,
                vstart = getVstart(simi)
            )

            
            /*dut.clock.step(2)
            // finalVxsat = finalVxsat || dut.io.out.bits.vxsat.peek().litValue == 1
            vd = dut.io.out.bits.vd.peek().litValue
            vdres = f"h$vd%032x".equals(expectvd(n_inputs - 1 - j))
            Logger.printvds(f"h$vd%032x", expectvd(n_inputs - 1 - j))
            if (!vdres) dump(simi, f"h$vd%032x", expectvd(n_inputs - 1 - j))
            assert(vdres)*/

            srcBundles :+= srcBundle
            ctrlBundles :+= ctrlBundle
        }
        // assert(finalVxsat == vxsat)
        return TestCase.newNormalCase(
            this.instid,
            srcBundles,
            ctrlBundles,
            resultChecker
        )
    }
}