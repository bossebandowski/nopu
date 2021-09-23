package util

import Chisel._

import patmos.Constants._

class OutputLogic() extends Module {
    val io = IO(new Bundle {
        val enable = Input(Bool ())
        val values = Input(Vec(10,SInt (32.W)))
        val result = Output(SInt (32.W))
    })
    
    val countReg = RegInit(UInt(0, 32))
    val maxIndex = RegInit(SInt(0, 32))
    val maxValue = RegInit(SInt(0, 32))


    when(io.enable)
    {
        switch(countReg){
            is(1.U){
                when(io.values(1) > maxValue){
                    maxValue := io.values(1)
                    maxIndex := 1.S
                }
            }
            is(2.U){
                when(io.values(2) > maxValue){
                    maxValue := io.values(2)
                    maxIndex := 2.S
                }
            }
            is(3.U){
                when(io.values(3) > maxValue){
                    maxValue := io.values(3)
                    maxIndex := 3.S
                }
            }
            is(4.U){
                when(io.values(4) > maxValue){
                    maxValue := io.values(4)
                    maxIndex := 4.S
                }
            }
            is(5.U){
                when(io.values(5) > maxValue){
                    maxValue := io.values(5)
                    maxIndex := 5.S
                }
            }
            is(6.U){
                when(io.values(6) > maxValue){
                    maxValue := io.values(6)
                    maxIndex := 6.S
                }
            }
            is(7.U){
                when(io.values(7) > maxValue){
                    maxValue := io.values(7)
                    maxIndex := 7.S
                }
            }
            is(8.U){
                when(io.values(8) > maxValue){
                    maxValue := io.values(8)
                    maxIndex := 8.S
                }
            }
            is(9.U){
                when(io.values(9) > maxValue){
                    maxValue := io.values(9)
                    maxIndex := 9.S
                }
            }

        }
        countReg := countReg + UInt(1)
    }
    .otherwise
    {
        countReg := UInt(0)
    }

    io.result := maxIndex
}