/*
simple coprocessor entity to explore accelerator interface
Author: Bosse Bandowski (bosse.bandowski@outlook.com)
*/

package cop

import chisel3._
import chisel3.util._
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
    val FUNC_GET_RES          = "b00100".U(5.W)   // read result register

    val FUNC_MEM_W            = "b00011".U(5.W)   // TEST: write a value into memory
    val FUNC_MEM_R            = "b00101".U(5.W)   // TEST: read a value from memory

    // states COP
    val idle :: start :: load_input_32 :: load_w_8 :: load_output :: mac_32 :: write_out :: load_b :: b_add_relu_0 :: b_add_relu_1 :: b_add :: b_wr :: read_output :: find_max_aux :: save_max :: mem_w :: mem_r :: restart :: reset_memory :: Nil = Enum(19)

    val stateReg = RegInit(0.U(8.W))
    val outputUsage = RegInit(0.U(8.W))


    // state MEM
    val memIdle :: memDone :: memReadReq :: memRead :: memWriteReq :: memWrite :: Nil = Enum(6)
    val memState = RegInit(memIdle)

    // auxiliary registers
    val addrReg = RegInit(0.U(DATA_WIDTH.W))
    val resReg = RegInit(10.U(DATA_WIDTH.W))
    val mem_w_buffer = Reg(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
    val mem_r_buffer = Reg(Vec(BURST_LENGTH, UInt(DATA_WIDTH.W)))
    val burst_count_reg = RegInit(0.U(3.W))
    val relu = Reg(Vec(BURST_LENGTH, SInt(DATA_WIDTH.W)))

    val maxIn = RegInit(0.U(DATA_WIDTH.W))
    val maxOut = RegInit(0.U(DATA_WIDTH.W))


    val inAddr = RegInit(0.U(DATA_WIDTH.W))
    val weightAddr = RegInit(0.U(DATA_WIDTH.W))
    val biasAddr = RegInit(0.U(DATA_WIDTH.W))
    val outAddr = RegInit(0.U(DATA_WIDTH.W))
    val layer = RegInit(0.U(1.W))
    

    val ws = Reg(Vec(BURST_LENGTH, SInt(8.W)))
    val ins32 = Reg(Vec(BURST_LENGTH, SInt(32.W)))
    val outs = Reg(Vec(BURST_LENGTH, SInt(32.W)))
    val bs = Reg(Vec(BURST_LENGTH, SInt(32.W)))
    val idx = RegInit(0.U(DATA_WIDTH.W))
    val curMax = RegInit(-2147483646.S(DATA_WIDTH.W))

    val inCount = RegInit(0.U(32.W))
    val outCount = RegInit(0.U(32.W))

/* ================================================= CONSTANTS ============================================= */ 

    // address constants
    val img_addr_0 = 30.U
    val w_1_addr_0 = 1000000.U
    val w_2_addr_0 = 1320000.U
    val b_1_addr_0 = 1325000.U
    val b_2_addr_0 = 1326000.U
    val n_addr_0 = 10000.U
    val n_addr_1 = 20000.U

    /* ============================================ CMD HANDLING ============================================ */ 

    val isIdle = Wire(Bool())
    isIdle := stateReg === idle && memState === memIdle

    // default values
    io.copOut.result := 10.U
    io.copOut.ena_out := false.B

    // start operation
    when(io.copIn.trigger && io.copIn.ena_in) {
        io.copOut.ena_out := true.B
        when(io.copIn.isCustom) {
        // no custom operations
        }.elsewhen(io.copIn.read) {
            switch(io.copIn.funcId) {
                is(FUNC_POLL) {
                    io.copOut.result := Cat(0.U((DATA_WIDTH-8).W), stateReg)
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
        }
        is(start) {
            /*
            Prepare for inference. Assumes that model and image are in the defined memory locations.
            If that is not the case by this state, the inference will go horribly wrong.
            */
            inAddr := img_addr_0                            // set first pixel addresss
            weightAddr := w_1_addr_0                        // set first weight address
            biasAddr := b_1_addr_0                          // set first bias address
            outAddr := n_addr_0                             // set first node address
            inCount := 0.U                                  // reset input count
            outCount := 0.U                                 // reset output count
            layer := 0.U                                    // reset layer count
            curMax := -2147483646.S                         // set curmax to low value


            outputUsage := mac_32                           // tell the load_output state where to transition next (mac or bias_add loop)

            maxIn := 783.U                                  // layer 0 has 784 input pixels
            maxOut := 96.U                                  // there are 100 nodes in layer 1 (last iteration at 96 because 4 calcs/iteration)

            idx := 0.U                                      // reset idx which is used to figure out the index of the maximum value in the output layer

            stateReg := load_input_32                       // start inference by loading pixels

        }

        is(load_input_32) {
            /*
            Read one burst of inputs and only keep the first one.
            For full word inputs only
            */

            when (memState === memIdle) {                   // wait until memory is ready
                addrReg := inAddr                           // load current input address
                memState := memReadReq                      // force memory into read request state
            }

            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle
                stateReg := load_w_8                        // load weights next

                ins32(0) := mem_r_buffer(0).asSInt          // load first input from read buffer (and discard the rest for now)
            }
        }
        is(load_w_8) {
            /*
            Read one burst of weights of 4 words. If weights are stored as full words, then this will amount to 4 weights
            */

            when (memState === memIdle) {                   // wait until memory is ready                   
                addrReg := weightAddr                       // load current weight address
                memState := memReadReq                      // force memory into read request state
            }

            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle
                stateReg := load_output                     // load outputs next

                
                
                ws(3) := mem_r_buffer(0)(7, 0).asSInt       
                ws(2) := mem_r_buffer(0)(15, 8).asSInt
                ws(1) := mem_r_buffer(0)(23, 16).asSInt
                ws(0) := mem_r_buffer(0)(31, 24).asSInt         
                
/* for 32 bit wide weights
                ws(0) := mem_r_buffer(0).asSInt
                ws(1) := mem_r_buffer(1).asSInt
                ws(2) := mem_r_buffer(2).asSInt
                ws(3) := mem_r_buffer(3).asSInt
*/
            }
        }
        is(load_output) {
            /*
            Load intermediate network nodes. Since the activations are 32 bits, a single burst returns
            4 intermediate network nodes. 
            */


            when (memState === memIdle) {                   // wait until memory is ready
                addrReg := outAddr                          // load current output address
                memState := memReadReq                      // force memory into read request state
            }

            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle
                stateReg := outputUsage                     // next state could be mac or bias add, depending on progress

                outs(0) := mem_r_buffer(0).asSInt           // load 4 outputs from read buffer
                outs(1) := mem_r_buffer(1).asSInt
                outs(2) := mem_r_buffer(2).asSInt
                outs(3) := mem_r_buffer(3).asSInt
            }      
        }
        is(mac_32) {
            /*
            Do the multiply-accumulate ops. One input to four outputs.
            */
            outs(0) := outs(0) + (ins32(0) * ws(0))
            outs(1) := outs(1) + (ins32(0) * ws(1))
            outs(2) := outs(2) + (ins32(0) * ws(2))
            outs(3) := outs(3) + (ins32(0) * ws(3))

            stateReg := write_out                           // next state will be output writeback

        }
        is(write_out) {
            /*
            write result back to memory (this could potentially be moved to on-chip memory).
            Further, defines how to continue based on which inputs and outputs have just been processed
            */

            when (memState === memIdle) {                   // wait until memory is ready
                mem_w_buffer(0) := outs(0).asUInt           // load outputs into write buffer
                mem_w_buffer(1) := outs(1).asUInt
                mem_w_buffer(2) := outs(2).asUInt
                mem_w_buffer(3) := outs(3).asUInt

                addrReg := outAddr                          // make sure that the right output address is still in addrReg
                memState := memWriteReq                     // force memory into write request state
            }

            when (memState === memDone) {                   // wait until transition is finished
                memState := memIdle                         // mark memory as idle

                // transitions
                when (inCount === maxIn && outCount === maxOut) {
                    /*
                    when all inputs have been processed and added to all outputs, the mac loop of the current layer is done.
                    Continue by resetting counts and addresses and move on to adding biases
                    */
                    outCount := 0.U                         // reset output count
                    stateReg := load_output                 // next state will be output load
                    outputUsage := load_b                   // after that, transition to bias load
                    when (layer === 0.U) {                  // set node address depending on current layer
                        outAddr := n_addr_0
                    }
                    .otherwise {
                        outAddr := n_addr_1
                    }
                }
                .elsewhen(inCount < maxIn && outCount === maxOut) {
                    /*
                    when the current input has been mac'ed to all outputs, continue to the next input
                    */
                    outCount := 0.U                         // reset output count
                    inCount := inCount + 1.U                // increment input count
                    inAddr := inAddr + 4.U                  // increment input address by one word
                    stateReg := load_input_32               // in the future, the input layer will map to a different loop because the inputs are less wide
                    weightAddr := weightAddr + 4.U         // increment weight address by four bytes
                    
                    when (layer === 0.U) {                  // reset node address based on current layer         
                        outAddr := n_addr_0         
                    }
                    .otherwise {
                        outAddr := n_addr_1
                    }
                }
                .otherwise {
                    /*
                    when the current input has not yet been mac'ed to all outputs, just continue with the next 4...
                    */
                    outCount := outCount + 4.U              // increment node count by 4
                    outAddr := outAddr + 16.U               // increment node address by 4 words
                    weightAddr := weightAddr + 4.U         // increment weight address by 4 bytes
                    stateReg := load_w_8                    // transition to load weight (same input, different outputs, different weights)
                }
            }
        }
        is(load_b) {
            /*
            load bias from memory. Biases are 32 bit wide, so we can read four at a time
            */

            when (memState === memIdle) {                   // wait until memory is ready
                addrReg := biasAddr                         // load current bias address
                memState := memReadReq                      // force memory into read request state
            }

            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle
                when (layer === 0.U) {                      // different layers have different activations and thus next state (relu or softmax).
                    stateReg := b_add_relu_0                // layer 0 uses relu
                }
                .otherwise {
                    stateReg := b_add                       // layer has softmax (applied after plain add)
                }

                bs(0) := mem_r_buffer(0).asSInt             // get biases from memory read buffer
                bs(1) := mem_r_buffer(1).asSInt
                bs(2) := mem_r_buffer(2).asSInt
                bs(3) := mem_r_buffer(3).asSInt
            }
        }
        is(b_add_relu_0) {
            /*
            add biases to nodes and apply relu
            */

            relu(0) := bs(0) + outs(0)                      // just add biases to corresponding outputs
            relu(1) := bs(1) + outs(1)
            relu(2) := bs(2) + outs(2)
            relu(3) := bs(3) + outs(3)

            stateReg := b_add_relu_1                        // cap negative values in the next state
        }
        is(b_add_relu_1) {
            /*
            finish relu activation by setting negative nodes equal to 0
            */
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

            stateReg := b_wr                                // get ready to write back outputs
        }
        is(b_add) {
            /*
            add biases to nodes without applying relu
            */

            relu(0) := bs(0) + outs(0)
            relu(1) := bs(1) + outs(1)
            relu(2) := bs(2) + outs(2)
            relu(3) := bs(3) + outs(3)

            stateReg := b_wr                                // get ready to write back outputs

        }
        is(b_wr) {
            /*
            write results of bias_add + activation back to memory
            */
            when (memState === memIdle) {                   // wait for memory to be ready
                mem_w_buffer(0) := relu(0).asUInt           // load outputs into write buffer
                mem_w_buffer(1) := relu(1).asUInt
                mem_w_buffer(2) := relu(2).asUInt
                mem_w_buffer(3) := relu(3).asUInt

                addrReg := outAddr                          // set output address
                memState := memWriteReq                     // request write transaction
            }

            when (memState === memDone) {                   // wait until transaction is finished
                memState := memIdle                         // mark memory as idle
            
                when (layer === 0.U) {                      // the state transitions depend on which layer we are in
                    when(outCount === maxOut) {             // when all biases have been added, prepare for layer 2
                        stateReg := load_input_32           // get ready to load nodes as inputs
                        outputUsage := mac_32               // from the load outputs state, we will transition to mac operations
                        layer := 1.U                        // set flag for second layer
                        inAddr := n_addr_0                  // set input address
                        outAddr := n_addr_1                 // set output address
                        weightAddr := w_2_addr_0            // set weight address to layer 2 array
                        biasAddr := b_2_addr_0              // set bias address to layer 2 array
                        inCount := 0.U                      // reset input count
                        outCount := 0.U                     // reset output count
                        maxIn := 99.U                       // in layer 2, there are only 100 inputs (former outputs)
                        maxOut := 8.U                       // and 10 outputs (10 - 10 % 4 = 8, will actually process 12 nodes of which 2 are ignored)
                    }
                    .otherwise{                             // when there are still biases left to be added, do another iteration
                        outCount := outCount + 4.U          // increment node count by 4
                        outAddr := outAddr + 16.U           // increment node address by 4 words
                        biasAddr := biasAddr + 16.U         // increment bias address by 4 words
                        stateReg := load_output             // continue with loading the next 4 nodes
                    }
                }
                .elsewhen (layer === 1.U) {
                    when(outCount === maxOut) {             // when all biases have been added, prepare for layer 2
                        stateReg := read_output             // get ready to extract max
                        outCount := 0.U                     // reset node count
                        maxOut := 9.U                       // set number of output nodes (now only processing one at a time)
                        outAddr := n_addr_1                 // set node address
                    }
                    .otherwise{
                        outCount := outCount + 4.U          // increment node count by 4
                        outAddr := outAddr + 16.U           // increment node address by 4 words
                        biasAddr := biasAddr + 16.U         // increment bias address by 4 words
                        stateReg := load_output             // continue with loading the next 4 nodes
                    }
                }
            }
        }
        is(read_output) {
            /*
            read a single node from the output layer
            */
            when (memState === memIdle) {                   // wait for memory to be ready
                addrReg := outAddr                          // set node address
                memState := memReadReq                      // force memory in ready request state
            }

            when (memState === memDone) {                   // wait until the transaction is finished
                memState := memIdle                         // mark memory as idle
                stateReg := find_max_aux                    // continue to next state in max extract loop
                outs(0) := mem_r_buffer(0).asSInt           // get first output from memory read buffer
            }
        }
        is(find_max_aux) {
            /*
            check if the current output node is larger than all previously seen ones
            */
            outAddr := outAddr + 4.U                        // increment node address by 1 word
            outCount := outCount + 1.U                      // increment node count by 1

            when (outs(0) > curMax) {                       // if output nodes is larger than current maximum output
                curMax := outs(0)                           // update current maximum output
                idx := outCount                             // update index of maximum output
            }

            when (outCount === maxOut) {                    // when we have cycled though all output nodes, exit loop                       
                stateReg := save_max
            }
            .otherwise {                                    // otherwise continue with next
                stateReg := read_output
            }
        }
        is(save_max) {
            /*
            copy index of maximum output into result register and set cop to idle (process finished)
            */
            resReg := idx
            stateReg := reset_memory
            maxIn := 100.U
            maxOut := 12.U
            inCount := 0.U
            outCount := 0.U
            inAddr := n_addr_0
            outAddr := n_addr_1
            layer := 0.U

            mem_w_buffer := Seq(0.U, 0.U, 0.U, 0.U)

        }
        is(reset_memory) {
            /*
            reset network nodes to 0 to be ready for next inference
            */
            when (memState === memIdle) {                   // wait until memory is ready
                when (layer === 0.U) {
                    when (inCount >= maxIn) {
                        layer := 1.U
                    }
                    .otherwise {
                        inCount := inCount + 4.U
                        inAddr := inAddr + 16.U
                        addrReg := inAddr
                        memState := memWriteReq
                    }
                }
                .elsewhen (layer === 1.U) {
                    when (outCount >= maxOut) {
                        stateReg := idle
                    }
                    .otherwise {
                        outCount := outCount + 4.U
                        outAddr := outAddr + 16.U
                        addrReg := outAddr
                        memState := memWriteReq
                    }
                }
            }

            when (memState === memDone) {                   // wait until transition is finished
                memState := memIdle                         // mark memory as idle
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
            addrReg := 0.U

            resReg := 10.U
            mem_r_buffer := Seq(0.U, 0.U, 0.U, 0.U)
            mem_w_buffer := Seq(0.U, 0.U, 0.U, 0.U)

            memState := memIdle
            stateReg := reset_memory
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