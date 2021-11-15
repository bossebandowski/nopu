import argparse
import sys

if __name__ == "__main__":
    args = sys.argv
    l_id = args[1]

    sf_path = f"/root/nopu/tmp/simulator_layer_{l_id}.txt"
    ef_path = f"/root/nopu/tmp/emulator_layer_{l_id}.txt"

    with open(sf_path) as sf:
        sf_lines = sf.read().splitlines()[3:]

    with open(ef_path) as ef:
        ef_lines = ef.read().splitlines()

    ef_0 = 0

    while not "0:" in ef_lines[ef_0][:2]:
        ef_0 += 1

    ef_lines = ef_lines[ef_0:]
    count = 0

    for i in range(len(sf_lines)):
        try:
            sf_val = int(sf_lines[i][sf_lines[i].find(" "):])

            ef_val = int(ef_lines[i][ef_lines[i].find(" "):])
            count += 1
                
            if (sf_val != ef_val):
                print(f"{i}: {sf_val}\t{ef_val}\tdiff:{ef_val-sf_val}")
        except:
            pass
            

    print("lines:", count)