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
    val idle :: start :: restart :: running :: mem_w :: mem_r :: load_px :: load_w :: load_b :: load_n :: calc_n :: write_n :: Nil = Enum(UInt(), 12)
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

    val readAddrP = RegInit(0.U(DATA_WIDTH.W))
    val readAddrW = RegInit(0.U(DATA_WIDTH.W))
    val readAddrB = RegInit(0.U(DATA_WIDTH.W))
    val readAddrN = RegInit(0.U(DATA_WIDTH.W))

    val nodeIdx = RegInit(0.U(DATA_WIDTH.W))
    val outputIdx = RegInit(0.U(DATA_WIDTH.W))

    val ws = Reg(Vec(BURST_LENGTH, 8.U))
    val pxs = Reg(Vec(BURST_LENGTH, 8.U))
    val ns = Reg(Vec(BURST_LENGTH, 32.U))

    val n_count_reg = RegInit(0.U(3.W))
    val iterations = RegInit(0.U(DATA_WIDTH.W))

/* ============================================= OLD CONSTANTS ============================================= */ 

    val layer2 = Reg(Vec(10, SInt(32.W)))

    val pReg = Reg(init = SInt(0, 32))
    val wReg = Reg(init = SInt(0, 32))
    val bReg = Reg(init = SInt(0, 32))
    val outputReg = Reg(init = UInt(10, 32))
    val nReg = Reg(init = SInt(0, 32))

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
            Don't do anything, really...
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
            iterations := 0.U                           // reset iteration count

            stateReg := load_px                         // transition to load pixel state
            io.copOut.ena_out := Bool(true)
        }
        is(load_px) {
            /*
            Read one burst of pixels (this will be 16 in total because each pixel is 1 byte and a single
            burst returns 4 words, so 4 x 4 = 16).
            */


            io.copOut.ena_out := Bool(true)

            when (memState === memIdle) {    
                addrReg := readAddrP                        // load current pixel base address into address reg
                readAddrP := readAddrP + 4.U               // increment readAddrP by 16 (burst length x (px / word))
                memState := memReadReq                  // force memory into read request state when idle
            }

            when (memState === memDone) {               // when done reading the burst, transition to load weights
                memState := memIdle
                stateReg := load_w

                pxs(0) := mem_r_buffer(0)(31,24)
                pxs(1) := mem_r_buffer(0)(23,16)
                pxs(2) := mem_r_buffer(0)(15,8)
                pxs(3) := mem_r_buffer(0)(7,0)
            }
        }
        is(load_w) {
            /*
            Read one burst of weights (again, 1 byte weights mean that a single burst returns 16 weights)
            */
            io.copOut.ena_out := Bool(true)

            when (memState === memIdle) {    
                addrReg := readAddrW                        // load current weight base address into address reg
                readAddrW := readAddrW + 4.U               // increment readAddrW by 16 (burst length x (weights / word))

                memState := memReadReq                  // force memory into read request state when idle
            }

            when (memState === memDone) {               // when done reading the burst, transition to mac
                memState := memIdle
                stateReg := load_n
                n_count_reg := 0.U

                ws(0) := mem_r_buffer(0)(31,24)
                ws(1) := mem_r_buffer(0)(23,16)
                ws(2) := mem_r_buffer(0)(15,8)
                ws(3) := mem_r_buffer(0)(7,0)
            }
        }
        is(load_n) {
            /*
            Load intermediate network nodes. Since the activations are 32 bits, a single burst only returns
            4 intermediate network nodes. Therefore, we need to perform 4 bursts to retrieve 16 nodes. 
            */

            io.copOut.ena_out := Bool(true)

            when (memState === memIdle) {    

                n_count_reg := n_count_reg + 1.U            // keep track of loop count
                addrReg := readAddrN                        // load current node base address into address reg

                memState := memReadReq                  // force memory into read request state when idle
            }

            when (memState === memDone) {               // when done reading the burst, transition to mac
                memState := memIdle
                stateReg := calc_n

                ns(0) := mem_r_buffer(0)
                ns(1) := mem_r_buffer(1)
                ns(2) := mem_r_buffer(2)
                ns(3) := mem_r_buffer(3)
            }      
        }

        is(calc_n) {
            ns(0) := ns(0) + Cat(0.U((DATA_WIDTH - 8).W), pxs(0) * ws(0))
            ns(1) := ns(0) + Cat(0.U((DATA_WIDTH - 8).W), pxs(1) * ws(1))
            ns(2) := ns(0) + Cat(0.U((DATA_WIDTH - 8).W), pxs(2) * ws(2))
            ns(3) := ns(0) + Cat(0.U((DATA_WIDTH - 8).W), pxs(3) * ws(3))

            stateReg := write_n
        }

        is(write_n) {

            when (memState === memIdle) {    
                mem_w_buffer(0) := ns(0)
                mem_w_buffer(0) := ns(1)
                mem_w_buffer(0) := ns(2)
                mem_w_buffer(0) := ns(3)

                addrReg := readAddrN                        // make sure that the right node base address is still in addrReg
                readAddrN := readAddrW + 4.U                // increment readAddrN by 4 (burst length)
                memState := memWriteReq                 // force memory into write request state when idle
            }

            when (memState === memDone) {               // when done reading the burst, transition to mac
                memState := memIdle

                ns(0) := mem_r_buffer(0)
                ns(1) := mem_r_buffer(1)
                ns(2) := mem_r_buffer(2)
                ns(3) := mem_r_buffer(3)

                // transitions
                when (n_count_reg < 4.U) {
                    stateReg := load_n
                }
                .elsewhen (iterations < 19600.U) {
                    // reset pixel addr if necessary!!! ===================================================================================
                    // reset pixel addr if necessary!!! ===================================================================================
                    // reset pixel addr if necessary!!! ===================================================================================
                    // reset pixel addr if necessary!!! ===================================================================================



                    iterations := iterations + 1.U
                    stateReg := load_px
                }
                .otherwise {
                    stateReg := load_b
                }
            }   
        }
    }

    when (stateReg === mem_w) {
        when (memState === memIdle) {
            memState := memWriteReq
        }

        when (memState === memDone) {
            memState := memIdle
            stateReg := idle
        }
    }

    when (stateReg === mem_r) {
        when (memState === memIdle) {
            memState := memReadReq
        }

        when (memState === memDone) {
            memState := memIdle
            stateReg := idle
            resReg := mem_r_buffer(0)
        }
    }

    when (stateReg === restart) {

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
                burst_count_reg := burst_count_reg + 1.U
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