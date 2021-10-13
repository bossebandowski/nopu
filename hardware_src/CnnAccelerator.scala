/*
simple coprocessor entity to explore accelerator interface
Author: Bosse Bandowski (bosse.bandowski@outlook.com)
*/

package cop

import Chisel._

import patmos.Constants._
import util._
import ocp._

object CnnAccelerator extends CoprocessorObject {
    
  def init(params: Map[String, String]) = {}

  def create(params: Map[String, String]): CnnAccelerator = Module(new CnnAccelerator())
}

class CnnAccelerator() extends CoprocessorMemoryAccess() {

    // coprocessor function definitions
    val FUNC_RESET            = "b00000".U(5.W)   // reset coprocessor
    val FUNC_POLL             = "b00001".U(5.W)   // get processor status
    val FUNC_RUN              = "b00010".U(5.W)   // init inference
    val FUNC_MEM_W            = "b00011".U(5.W)   // TEST: write a value into memory
    val FUNC_MEM_R            = "b00101".U(5.W)   // TEST: read a value from memory
    val FUNC_GET_RES          = "b00100".U(5.W)   // TEST: read a result

    // states COP
    val idle :: start :: restart :: running :: mem_w :: mem_r :: load_px :: load_w :: load_b :: load_n :: calc_n :: write_n :: b_add_0 :: b_wr_0 :: Nil = Enum(UInt(), 14)
    val stateReg = Reg(init = idle)

    // state MEM
    val memIdle :: memDone :: memReadReq :: memRead :: memWriteReq :: memWrite :: Nil = Enum(UInt(), 6)
    val memState = RegInit(memIdle)

    // auxiliary registers
    val addrReg = RegInit(0.U(DATA_WIDTH.W))
    val resReg = RegInit(10.U(DATA_WIDTH.W))
    val mem_w_buffer = Reg(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
    val mem_r_buffer = Reg(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
    val burst_count_reg = RegInit(0.U(3.W))
    val relu = Reg(Vec(BURST_LENGTH, SInt(DATA_WIDTH.W)))


    val readAddrP = RegInit(0.U(DATA_WIDTH.W))
    val readAddrW = RegInit(0.U(DATA_WIDTH.W))
    val readAddrB = RegInit(0.U(DATA_WIDTH.W))
    val readAddrN = RegInit(0.U(DATA_WIDTH.W))

    val nodeIdx = RegInit(0.U(DATA_WIDTH.W))
    val outputIdx = RegInit(0.U(DATA_WIDTH.W))

    val ws = Reg(Vec(BURST_LENGTH, SInt(8.W)))
    val pxs = Reg(Vec(BURST_LENGTH, SInt(8.W)))
    val ns = Reg(Vec(BURST_LENGTH, SInt(32.W)))
    val bs = Reg(Vec(BURST_LENGTH, SInt(32.W)))

    val pxCount = RegInit(0.U(32.W))
    val nCount = RegInit(0.U(32.W))

    val loop_weights :: add_biases :: layer_2 :: Nil = Enum(UInt(), 3)
    val progress = Reg(init = loop_weights)

/* ================================================= CONSTANTS ============================================= */ 

    // address constants
    val img_addr_0 = 30.U
    val w_1_addr_0 = 900.U
    val w_2_addr_0 = 80000.U
    val b_1_addr_0 = 82000.U
    val b_2_addr_0 = 83000.U
    val n_addr_0 = 84000.U

    /* ============================================ CMD HANDLING ============================================ */ 

    val isIdle = Wire(Bool())
    isIdle := stateReg === idle && memState === memIdle

    // default values
    io.copOut.result := 0.U
    io.copOut.ena_out := Bool(false)

    // start operation
    when(io.copIn.trigger && io.copIn.ena_in) {
        io.copOut.ena_out := Bool(true)
        when(io.copIn.isCustom) {
        // no custom operations
        }.elsewhen(io.copIn.read) {
            switch(io.copIn.funcId) {
                is(FUNC_POLL) {
                    io.copOut.result := Cat(UInt(0, width = DATA_WIDTH - 3), stateReg)
                }
                is(FUNC_GET_RES) {
                    io.copOut.result := resReg
                }
            }
        }
        .otherwise {
            switch(io.copIn.funcId) {
                is(FUNC_RESET) {
                    when(isIdle) {
                        stateReg := restart
                    }
                }
                is(FUNC_RUN) {
                    when(isIdle) {
                        stateReg := start
                    }
                }
                is(FUNC_MEM_W) {
                    when(isIdle) {
                        addrReg := io.copIn.opData(0)
                        mem_w_buffer(0) := io.copIn.opData(1)
                        stateReg := mem_w
                    }
                }
                is(FUNC_MEM_R) {
                    when(isIdle) {
                        addrReg := io.copIn.opData(0)
                        stateReg := mem_r
                    }
                }
                
            }
        }
    }

    /* ===================================== ACCELERATOR STATE MACHINE ====================================== */

    switch(stateReg) {
        is(idle) {
            /*
            Don't do anything until asked to
            */
            io.copOut.ena_out := Bool(true)
        }
        is(start) {
            /*
            Prepare for inference. Assumes that model and image are in the defined memory locations.
            If that is not the case by this state, the inference will go horribly wrong.
            */
            readAddrP := img_addr_0                     // reset pixel addresss
            readAddrW := w_1_addr_0                     // reset weight address
            readAddrB := b_1_addr_0                     // reset bias address
            readAddrN := n_addr_0                       // reset node address
            nCount := 0.U                               // reset node count
            pxCount := 0.U                              // reset pixel count
            progress := loop_weights                    // reset flag that tells load_n state where to go next

            stateReg := load_px                         // transition to load pixel state

            io.copOut.ena_out := Bool(true)
        }
        is(load_px) {
            /*
            Read one burst of pixels and only keep the first one (for now)
            */


            io.copOut.ena_out := Bool(true)

            when (memState === memIdle) {    
                addrReg := readAddrP                        // load current pixel base address into address reg
                memState := memReadReq                      // force memory into read request state when idle
            }

            when (memState === memDone) {                   // when done reading the burst, transition to load weights
                memState := memIdle
                stateReg := load_w

                pxs(0) := mem_r_buffer(0)(31,24).asSInt
                // pxs(1) := mem_r_buffer(0)(23,16)
                // pxs(2) := mem_r_buffer(0)(15,8)
                // pxs(3) := mem_r_buffer(0)(7,0)
            }
        }
        is(load_w) {
            /*
            Read one burst of weights (again, 1 byte weights mean that a single burst returns 16 weights)
            */
            io.copOut.ena_out := Bool(true)

            when (memState === memIdle) {    
                addrReg := readAddrW                        // load current weight base address into address reg
                memState := memReadReq                      // force memory into read request state when idle
            }

            when (memState === memDone) {                   // when done reading the burst, transition to load nodes
                memState := memIdle
                stateReg := load_n

                ws(0) := mem_r_buffer(0)(31,24).asSInt
                ws(1) := mem_r_buffer(0)(23,16).asSInt
                ws(2) := mem_r_buffer(0)(15,8).asSInt
                ws(3) := mem_r_buffer(0)(7,0).asSInt
            }
        }
        is(load_n) {
            /*
            Load intermediate network nodes. Since the activations are 32 bits, a single burst only returns
            4 intermediate network nodes. 
            */

            io.copOut.ena_out := Bool(true)

            when (memState === memIdle) {    
                addrReg := readAddrN                        // load current node base address into address reg
                memState := memReadReq                      // force memory into read request state when idle
            }

            when (memState === memDone) {                   // when done reading the burst, transition to mac
                memState := memIdle
                switch(progress) {
                    is(loop_weights) {
                        stateReg := calc_n
                    }
                    is(add_biases) {
                        stateReg := b_add_0
                    }
                    is(layer_2) {
                        stateReg := idle
                    }
                }

                ns(0) := mem_r_buffer(0).asSInt
                ns(1) := mem_r_buffer(1).asSInt
                ns(2) := mem_r_buffer(2).asSInt
                ns(3) := mem_r_buffer(3).asSInt
            }      
        }
        is(calc_n) {
            /*
            Do the mac operations (4 at a time, this is limited by the number of nodes we can read per burst)
            */

            io.copOut.ena_out := Bool(true)

            ns(0) := ns(0) + pxs(0) * ws(0)
            ns(1) := ns(1) + pxs(0) * ws(1)
            ns(2) := ns(2) + pxs(0) * ws(2)
            ns(3) := ns(3) + pxs(0) * ws(3)

            stateReg := write_n
        }
        is(write_n) {
            /*
            write result back to memory (this could potentially be moved to on-chip memory).
            Further, defines how to continue based on which pixel and nodes have just been processed
            */
            io.copOut.ena_out := Bool(true)

            when (memState === memIdle) {    
                mem_w_buffer(0) := ns(0).asUInt
                mem_w_buffer(1) := ns(1).asUInt
                mem_w_buffer(2) := ns(2).asUInt
                mem_w_buffer(3) := ns(3).asUInt

                addrReg := readAddrN                        // make sure that the right node base address is still in addrReg
                memState := memWriteReq                     // force memory into write request state when idle
            }

            when (memState === memDone) {                   // when done reading the burst, transition to mac
                memState := memIdle

                // transitions

                // when pxCount == 784 && nCount == 100, we are done and continue with biases
                when (pxCount === 784.U && nCount === 100.U) {
                    nCount := 0.U                           // reset node count            
                    readAddrN := n_addr_0                   // reset node address
                    stateReg := load_b                      // get ready to add biases
                }
                // when pxCount < 784 && nCount = 100 it needs to be reset to 0 and the next pixel will be used as input
                .elsewhen(pxCount < 784.U && nCount === 100.U) {
                    nCount := 0.U                           // reset node count            
                    readAddrN := n_addr_0                   // reset node address
                    pxCount := pxCount + 1.U                // increment pixel count
                    readAddrP := readAddrP + 1.U            // increment pixel address
                    readAddrW := readAddrW + 1.U            // increment weight address
                    stateReg := load_px                     // transition to load pixel state
                }
                // when pxCount < 784 && nCount < 100, we just look and the next 4 weights to the next four nodes
                .otherwise {
                    nCount := nCount + 4.U                  // increment node count by 4
                    readAddrN := readAddrN + 4.U            // increment node address by 4
                    readAddrW := readAddrW + 1.U            // increment weight address by 1
                    stateReg := load_w                      // transition to load weight (same pixel, different nodes, different weights)
                }
            }
        }
        is(load_b) {
            /*
            load bias from memory and add to nodes. Biases are 32 bit wide, so we can read four at a time
            */
            progress := add_biases
            io.copOut.ena_out := Bool(true)

            when (memState === memIdle) {    
                addrReg := readAddrB                        // load current bias base address into address reg
                memState := memReadReq                      // force memory into read request state when idle
            }

            when (memState === memDone) {                   // when done reading the burst, transition to loading nodes
                memState := memIdle
                stateReg := load_n

                bs(0) := mem_r_buffer(0).asSInt
                bs(1) := mem_r_buffer(1).asSInt
                bs(2) := mem_r_buffer(2).asSInt
                bs(3) := mem_r_buffer(3).asSInt
            }
        }
        is(b_add_0) {
            /*
            add biases to nodes and apply relu
            */
            relu(0) := bs(0) + ns(0)
            relu(1) := bs(1) + ns(1)
            relu(2) := bs(2) + ns(2)
            relu(3) := bs(3) + ns(3)

            when (relu(0) < 0.S) {
                relu(0) := 0.S
            }
            when (relu(1) < 0.S) {
                relu(1) := 0.S
            }
            when (relu(2) < 0.S) {
                relu(2) := 0.S
            }
            when (relu(3) < 0.S) {
                relu(3) := 0.S
            }

            stateReg := b_wr_0

        }
        is(b_wr_0) {
            io.copOut.ena_out := Bool(true)

            when (memState === memIdle) {    
                mem_w_buffer(0) := relu(0).asUInt
                mem_w_buffer(1) := relu(1).asUInt
                mem_w_buffer(2) := relu(2).asUInt
                mem_w_buffer(3) := relu(3).asUInt

                addrReg := readAddrN
                memState := memWriteReq
            }

            when (memState === memDone) {
                memState := memIdle
            
                when(nCount === 100.U) {
                    stateReg := idle
                }
                .otherwise{
                    nCount := nCount + 4.U                          // increment node count by 4
                    readAddrN := readAddrN + 4.U                    // increment node address by 4
                    readAddrB := readAddrB + 4.U                    // increment bias address by 4
                    stateReg := load_b                              // continue with loading the next 4 biases
                }
            }
        }
        is(mem_w) {
            when (memState === memIdle) {
                memState := memWriteReq
            }

            when (memState === memDone) {
                memState := memIdle
                stateReg := idle
            }
        }
        is(mem_r) {
            when (memState === memIdle) {
                memState := memReadReq
            }

            when (memState === memDone) {
                memState := memIdle
                stateReg := idle
                resReg := mem_r_buffer(0)
            }
        }
        is(restart) {
            burst_count_reg := 0.U
            mem_r_buffer := Vec(Seq(0.U, 0.U, 0.U, 0.U))
            mem_w_buffer := Vec(Seq(0.U, 0.U, 0.U, 0.U))
            addrReg := 0.U

            io.copOut.result := 0.U
            io.copOut.ena_out := Bool(false)
            resReg := 10.U

            memState := memIdle
            stateReg := idle
        }
    }


/* ================================================ Memory ================================================== */

    // defaults
    io.memPort.M.Cmd := OcpCmd.IDLE
    io.memPort.M.Addr := 0.U
    io.memPort.M.Data := 0.U
    io.memPort.M.DataValid := 0.U
    io.memPort.M.DataByteEn := "b1111".U

    // memory state machine
    switch(memState) {
        is(memReadReq) {
            io.memPort.M.Cmd := OcpCmd.RD
            io.memPort.M.Addr := addrReg
            burst_count_reg := 0.U
            when(io.memPort.S.CmdAccept === 1.U) {
                memState := memRead
            }
        }
        is(memRead) {
            mem_r_buffer(burst_count_reg) := io.memPort.S.Data
            when(io.memPort.S.Resp === OcpResp.DVA) {
                burst_count_reg := burst_count_reg + 1.U
            }
            when (burst_count_reg + 1.U === BURST_LENGTH.U) {
                memState := memDone
            }
        }
        is(memWriteReq) {
            io.memPort.M.Cmd := OcpCmd.WR
            io.memPort.M.Addr := addrReg
            io.memPort.M.Data := mem_w_buffer(0)
            io.memPort.M.DataValid := 1.U
            when(io.memPort.S.CmdAccept === 1.U && io.memPort.S.DataAccept === 1.U) {
                burst_count_reg := 1.U
                memState := memWrite
            }
        }
        is(memWrite) {
            io.memPort.M.Data := mem_w_buffer(burst_count_reg);
            io.memPort.M.DataValid := 1.U
            when(io.memPort.S.DataAccept === 1.U) {
                burst_count_reg := burst_count_reg + 1.U
            }

            when(io.memPort.S.Resp === OcpResp.DVA) {
                memState := memDone
            }
        }
    }
}