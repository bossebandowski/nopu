package io

import Chisel._
import chisel3.Driver

import patmos.Constants._

import ocp._
import util._

object Accelerator extends DeviceObject {

  def init(params: Map[String, String]) = {}

  def create(params: Map[String, String]): Accelerator = Module(new Accelerator())
}

class Accelerator() extends CoreDevice() {
  val readAddrP = Reg(init = UInt(0, 18))
  val readAddrW = Reg(init = UInt(0, 18))
  val readAddrB = Reg(init = UInt(0, 18))
  val writeAddr = Reg(init = UInt(0, 18))
  
  val nodeIdx = Reg(init = UInt(0, 18))
  val outputIdx = Reg(init = UInt(0, 18))

  val layer2 = Reg(Vec(10, SInt(32.W)))
  
  val masterReg = Reg(next = io.ocp.M)

  val pReg = Reg(init = SInt(0, 32))
  val wReg = Reg(init = SInt(0, 32))
  val bReg = Reg(init = SInt(0, 32))
  val outputReg = Reg(init = UInt(10, 32))
  val nReg = Reg(init = SInt(0, 32))

  // memory
  val memory = Module(new MemorySInt(18, 32))

  // address constants
  val imgAddrZero = UInt(0)
  val weightAddrZero = UInt(784)
  val biasAddrZero = UInt(80184)
  val nodeAddrZero = UInt(80293)

  // Default OCP response
  io.ocp.S.Resp := OcpResp.NULL
  io.ocp.S.Data := UInt(15, width = 32)

  //Memort test
  val memoryAddress = Reg(init = UInt(783,18))

  // network constants

  // states
  val noNN :: loadNN :: idle :: infLoad :: nextNode :: loadpx :: mac0  :: addb0 :: writeNode :: nextOutput :: loadNode :: mac1 :: addb1 :: writeRes :: clearMem :: clearOut :: Nil = Enum(UInt(), 16)
  //  0       1         2       3          4           5         6        7        8            9             10          11      12       13          14          15          16                
  val stateReg = Reg(init = noNN)

  // set memory address. Only for debugging
  when (masterReg.Cmd === OcpCmd.WR && masterReg.Addr(4,0) === 0x10.U) {
    io.ocp.S.Resp := OcpResp.DVA
    memoryAddress := masterReg.Data
  }
  .otherwise {
    // state machine goes here

    when (stateReg === noNN && masterReg.Cmd === OcpCmd.WR) {
      /*
      initial state. No network is loaded, so inference is not possible.
      Waiting for a write command and data == 0 to transition to load network state
      */
      io.ocp.S.Resp := OcpResp.DVA
      when (masterReg.Data === UInt(0)) {
        stateReg := loadNN
        writeAddr := weightAddrZero
      }
    }

    when (stateReg === loadNN && masterReg.Cmd === OcpCmd.WR) {
      /*
      load neural network state. whenever we get a write command, we store the current ocp data val
      in memory and increment the memory address
      */
      io.ocp.S.Resp := OcpResp.DVA
      memory.io.wrEna := true.B
      memory.io.wrAddr := writeAddr
      memory.io.wrData := (masterReg.Data).asSInt
      when (writeAddr === nodeAddrZero) {
        stateReg := idle
      }
      writeAddr := writeAddr + UInt(1)
    }

    when (stateReg === idle && masterReg.Cmd === OcpCmd.WR) {
      /*
      idle state. The accelerator has a network loaded in its memory and it is waiting for
      commands from the patmos. You can either load a new network by sending "0" or start
      the inference process by sending "1"
      */
      io.ocp.S.Resp := OcpResp.DVA
      memory.io.wrEna := false.B
      when(masterReg.Data(0) === UInt(0)) {
        stateReg := loadNN
        writeAddr := weightAddrZero
      }
      .otherwise {
        stateReg := infLoad
        writeAddr := imgAddrZero
      }
    }

    when (stateReg === infLoad && masterReg.Cmd === OcpCmd.WR) {
      /*
      first inference state. In order to run inference, you must first load an image into
      the accelerator memory. Provide sequential pixel values on the ocp data line and they
      will be stored in memory. The address increments automatically.
      */
      io.ocp.S.Resp := OcpResp.DVA
      memory.io.wrEna := true.B
      memory.io.wrAddr := writeAddr
      memory.io.wrData := (masterReg.Data).asSInt
      when (writeAddr === weightAddrZero - UInt(1)) {
        readAddrP := imgAddrZero
        readAddrW := weightAddrZero
        readAddrB := biasAddrZero
        memory.io.rdAddr := imgAddrZero
        stateReg := nextNode
      }
      writeAddr := writeAddr + UInt(1)
    }

    when (stateReg === nextNode) {
      /*
      the image in the memory is analysed node by node. For every node, we load all 784 pixels with their
      corresponding weights to the node, multiply, and increment. Once all pixels have been analysed, we write
      the node value to memory and  transition to the next node. No responses to write commands are issued while
      the accelerator is busy with inference.
      */
      readAddrP := UInt(0)                  // reset the pixel address. Every node starts with pixel 0
      readAddrW := weightAddrZero + nodeIdx // set the weight address to the first weight of the given node
      readAddrB := biasAddrZero + nodeIdx   // set the bias address to the bias of the given node
      nReg := SInt(0)                       // reset the register that contains temporary node values
      memory.io.rdAddr := UInt(0)           // next we want to load the first pixel at memory addr 0
      memory.io.wrEna := false.B            // read only
      stateReg := loadpx
    }

    when (stateReg === loadpx) {
      /*
      load a single pixel and move on to loading the corresponding weight
      */

      pReg := memory.io.rdData              // read pixel value into corresponding register
      readAddrP := readAddrP + UInt(1)      // increment pixel address to be ready for next read
      memory.io.rdAddr := readAddrW         // we will read a weight in the next state. Set address in preparation
      readAddrW := readAddrW + UInt(100)    // increment weight addr by 100 (there are 100 nodes, so every 100th weight matters)
      stateReg := mac0
    }

    when (stateReg === mac0) {
      /*
      multiply pixel value and weight and accumulate in temporary node reg. Weight value comes directly from memory (this saves 1 clock cycle per iteration, or ~33%)
      */
      when (readAddrP <= weightAddrZero) {      // when we have not yet analysed all pixels for a node
        nReg := nReg + pReg * memory.io.rdData  // increment node value with product of pixel and weight
        memory.io.rdAddr := readAddrP           // update memory address for next cycle
        stateReg := loadpx
      }
      .otherwise {                              // when we have multiplied all pixels with their weights
        memory.io.rdAddr := readAddrB           // prepare for bias load.
        stateReg := addb0
      }
    }

    when (stateReg === addb0) {
      /*
      Add bias to node. Apply RELU
      */
                                                
      val relu = nReg + memory.io.rdData            // add bias to node (directly from memory to save clock cycles)

      when (relu > SInt(0)) {                       // standard tensorflow relu: max(0, x)
        nReg := relu
      } .otherwise {
        nReg := SInt(0)
      }

      stateReg := writeNode
    }

    when (stateReg === writeNode) {
      /*
      Write node to memory
      */
      memory.io.wrEna := true.B
      memory.io.wrAddr := nodeAddrZero + nodeIdx
      memory.io.wrData := nReg

      when (nodeIdx < UInt(100)) {                  // when we're not done with all nodes in the hidden layer
        nodeIdx := nodeIdx + UInt(1)                // increment node index for next cycle
        stateReg := nextNode
      }
      .otherwise {                                  // when we are done with all nodes in the hidden layer
        outputIdx := UInt(0)                        // make sure that the output index is zero when starting second layer inference
        stateReg := nextOutput
      }
    }

    when (stateReg === nextOutput) {
      /*
      the first state in the second layer, corresponding to state "nextNode" in the first one.
      However, instead of loading pixel values, we load the previously calculated values of the hidden layer.
      */
      nodeIdx := UInt(0)                    // reset the node index. Every output starts with node 0
      readAddrW := biasAddrZero - UInt(1000) + outputIdx
                                            // set the weight address to the first weight of the given output
      readAddrB := biasAddrZero + UInt(100) + outputIdx   // set the bias address to the bias of the given node
      nReg := SInt(0)                       // reset the register that contains temporary output values
      memory.io.rdAddr := nodeAddrZero      // next we want to load the first node at memory addr nodeAddrZero
      memory.io.wrEna := false.B            // read only
      stateReg := loadNode
    }

    when (stateReg === loadNode) {
      /*
      load a single node and move on to loading the corresponding weight
      */

      pReg := memory.io.rdData              // load node into pReg because it is not used anymore
      memory.io.rdAddr := readAddrW         // we will read a weight in the next state. Set address in preparation
      readAddrW := readAddrW + UInt(10)     // increment weight addr by 10 (there are 10 outputs, so every 10th weight matters)
      nodeIdx := nodeIdx + UInt(1)
      stateReg := mac1
    }

    when (stateReg === mac1) {
      /*
      calculating second layer nodes. Multiply nodes with corresponding weights and add to tmp reg.
      Weight is coming directly from memory to save clock cycles.
      */
      nReg := nReg + pReg * memory.io.rdData      // increment node value with product of node and weight

      when (nodeIdx < UInt(100)) {                 // when we have not yet analysed all nodes for an output
        memory.io.rdAddr := nodeAddrZero + nodeIdx  // update memory address for next cycle
        stateReg := loadNode
      }
      .otherwise {                                  // when we have multiplied all pixels with their weights
        memory.io.rdAddr := readAddrB               // prepare for bias load.
        stateReg := addb1
      }
    }

    when (stateReg === addb1) {
      /*
      adding bias to output nodes and store in corresponding register
      */

      layer2(outputIdx) := nReg + memory.io.rdData  // add bias to output (directly from memory to save clock cycles)
      
      when (outputIdx < UInt(9)) {
        outputIdx := outputIdx + UInt(1)
        stateReg := nextOutput
      }
      .otherwise {
        stateReg := writeRes
      }
      

    }
    when (stateReg === writeRes) {
      /*
      extracting the index of the maximum value in the output layer.
      Just a bunch of multiplexors with >= comparators as flags.
      After this state, outputReg holds the inference result.
      */
      val max01 = Wire(0.U(32.W))
      val val01 = Wire(0.S(32.W))
      val max23 = Wire(0.U(32.W))
      val val23 = Wire(0.S(32.W))
      val max45 = Wire(0.U(32.W))
      val val45 = Wire(0.S(32.W))
      val max67 = Wire(0.U(32.W))
      val val67 = Wire(0.S(32.W))
      val max89 = Wire(0.U(32.W))
      val val89 = Wire(0.S(32.W))

      when (layer2(UInt(0))>=layer2(UInt(1))) {
        max01 := UInt(0)
        val01 := layer2(UInt(0))
      }.otherwise {
        max01 := UInt(1)
        val01 := layer2(UInt(1))
      }

      when (layer2(UInt(2))>=layer2(UInt(3))) {
        max23 := UInt(2)
        val23 := layer2(UInt(2))
      }.otherwise {
        max23 := UInt(3)
        val23 := layer2(UInt(3))
      }

      when (layer2(UInt(4))>=layer2(UInt(5))) {
        max45 := UInt(4)
        val45 := layer2(UInt(4))
      }.otherwise {
        max45 := UInt(5)
        val45 := layer2(UInt(5))
      }

      when (layer2(UInt(6))>=layer2(UInt(7))) {
        max67 := UInt(6)
        val67 := layer2(UInt(6))
      }.otherwise {
        max67 := UInt(7)
        val67 := layer2(UInt(7))
      }

      when (layer2(UInt(8))>=layer2(UInt(9))) {
        max89 := UInt(8)
        val89 := layer2(UInt(8))
      }.otherwise {
        max89 := UInt(9)
        val89 := layer2(UInt(9))
      }

      val max0123 = Wire(0.U(32.W))
      val val0123 = Wire(0.S(32.W))
      val max4567 = Wire(0.U(32.W))
      val val4567 = Wire(0.S(32.W))

      when (val01 >= val23) {
        max0123 := max01
        val0123 := val01
      } .otherwise {
        max0123 := max23
        val0123 := val23
      }

      when (val45 >= val67) {
        max4567 := max45
        val4567 := val45
      } .otherwise {
        max4567 := max67
        val4567 := val67
      }

      val max127 = Wire(0.U(32.W))
      val val127 = Wire(0.S(32.W))

      when (val0123 >= val4567) {
        max127 := max0123
        val127 := val0123
      } .otherwise {
        max127 := max4567
        val127 := val4567
      }

      when (val127 >= val89) {
        outputReg := max127
      }.otherwise {
        outputReg := max89
      }

      nodeIdx := UInt(0)
      stateReg := clearMem
    }

    when (stateReg === clearMem) {
      /*
      reset all node memory entries to 0
      */
      
      memory.io.wrEna := true.B
      memory.io.wrAddr := nodeAddrZero + nodeIdx
      memory.io.wrData := SInt(0)

      when (nodeIdx < UInt(99)) {
        nodeIdx := nodeIdx + UInt(1)
        stateReg := clearMem
      }
      .otherwise {
        stateReg := clearOut
      }
    }

    when (stateReg === clearOut) {
      /*
      reset all output registers to 0
      */
      outputIdx := UInt(0)
      nodeIdx := UInt(0)

      layer2(UInt(0)) := SInt(0)
      layer2(UInt(1)) := SInt(0)
      layer2(UInt(2)) := SInt(0)
      layer2(UInt(3)) := SInt(0)
      layer2(UInt(4)) := SInt(0)
      layer2(UInt(5)) := SInt(0)
      layer2(UInt(6)) := SInt(0)
      layer2(UInt(7)) := SInt(0)
      layer2(UInt(8)) := SInt(0)
      layer2(UInt(9)) := SInt(0)

      stateReg := idle
    }
  }

  // handle reads. Only for returning results and debugging
  when(masterReg.Cmd === OcpCmd.RD){
    io.ocp.S.Resp := OcpResp.DVA
    switch(masterReg.Addr(4,0)) {
      //Read status
      is(0x4.U) { 
        io.ocp.S.Data := stateReg
      }
      //Read value from memory
      is(0xC.U) { 
        memory.io.rdAddr := memoryAddress
        io.ocp.S.Data := (memory.io.rdData).asUInt
      }
      //Read result
      is(0x8.U) {
        io.ocp.S.Data := outputReg
      }
    }
  }
}

/*
 instantiate component for synthesis
 */
object AcceleratorMain extends App {
  chisel3.Driver.execute(args, () => new Accelerator())
}