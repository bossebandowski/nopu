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
    val FUNC_LOAD_M           = "b10000".U(5.W)   // TMP: PATMOS SHOULD BE RESPONSIBLE FOR LOADING THE MODEL. REMOVE HERE

    // states COP
    val idle :: start :: restart :: running :: mem_w :: mem_r :: Nil = Enum(UInt(), 6)
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
                        mem_w_buffer(1) := io.copIn.opData(1) + 1.U
                        mem_w_buffer(2) := io.copIn.opData(1) + 2.U
                        mem_w_buffer(3) := io.copIn.opData(1) + 3.U

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

    val BASE_ADDR = 0.U
    val BASE_VAL = 10.U
    val MAX_ROUNDS = 100000.U
    val i = RegInit(0.U(32.W))

    when (stateReg === idle) {
        io.copOut.ena_out := Bool(true)
    }

    when (stateReg === start) {
        i := 0.U
        stateReg := running
    }

    // do some cycles of nothing
    when (stateReg === running) {
        when (i < MAX_ROUNDS) {
            i := i + 1.U
        }
        .otherwise {
            stateReg := idle
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
        i := 0.U

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

    // memory logic (note: this assumes that all addresses are burst-aligned!)
    io.memPort.M.Cmd := OcpCmd.IDLE
    io.memPort.M.Addr := 0.U
    io.memPort.M.Data := 0.U
    io.memPort.M.DataValid := 0.U
    io.memPort.M.DataByteEn := "b1111".U

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