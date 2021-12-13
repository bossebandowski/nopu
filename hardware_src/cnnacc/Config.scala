package cnnacc

import chisel3._
import chisel3.util._

object Config {
    val MAX_LAYERS = 20
    val MAX_CONVOLUTIONS = 32
    val ABS_MIN = -2147483646
    val IMG_CHUNK_SIZE = 3072.U
    
    // states COP control
    val idle :: start :: fc :: conv :: pool :: mem_r :: restart :: reset_memory :: next_layer :: layer_done :: read_output :: find_max :: save_max :: load_image :: write_bram :: clear_layer :: peek_bram :: set_offset :: config :: Nil = Enum(19)
    // states FC layer
    val fc_idle :: fc_done :: fc_init :: fc_load_input :: fc_load_weight :: fc_load_output :: fc_mac :: fc_write_output :: fc_load_bias :: fc_add_bias :: fc_apply_relu :: fc_write_bias :: fc_requantize :: fc_load_m :: Nil = Enum(14)
    // states CONV layer
    val conv_idle :: conv_done :: conv_init :: conv_load_filter :: conv_apply_filter :: conv_write_output :: conv_load_bias :: conv_add_bias :: conv_apply_relu :: conv_load_input :: conv_rd_addr_set :: conv_wr_addr_set :: conv_load_output :: conv_load_m :: conv_requantize :: conv_sum_output :: Nil = Enum(16)
    // states POOL layer
    val pool_idle :: pool_done :: pool_init :: pool_in_addr_set :: pool_find_max :: pool_write_output :: pool_rd_delay :: pool_wr_delay :: Nil = Enum(8)
    // states SRAM control
    val mem_idle :: mem_done :: mem_read_req :: mem_read :: mem_write_req :: mem_write :: Nil = Enum(6)

    // coprocessor function definitions
    val FUNC_RESET            = "b00000".U(5.W)   // reset coprocessor
    val FUNC_POLL             = "b00001".U(5.W)   // get processor status
    val FUNC_RUN              = "b00010".U(5.W)   // init inference
    val FUNC_GET_RES          = "b00100".U(5.W)   // read result register

    val FUNC_CONFIG           = "b00011".U(5.W)   // receive a network configuration
    val FUNC_LOAD_IMG         = "b00110".U(5.W)   // write img pixel to BRAM
    val FUNC_MEM_R            = "b00101".U(5.W)   // TEST: read a value from memory
}
