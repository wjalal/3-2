#!/bin/bash
dir="scratch/plots"
rm "$dir/"*

models=("TcpWestwoodPlus" "TcpHighSpeed" "TcpAdaptiveReno")

for model in ${models[*]}; do

    ./ns3 run "scratch/1905084 --tcpModel=$model --plotX=congestion"
    gnuplot -e "set terminal png size 640,480; set xlabel 'Time (s)'; set ylabel 'Congestion Window'; plot '$dir/congestionReno_$model.dat' using 1:2 title 'TcpNewReno' with linespoints linestyle 3, '$dir/congestionOther_$model.dat' using 1:2 title '$model' with linespoints linestyle 7" > "$dir/congestion__$model.png"

    i=1
    ./ns3 run "scratch/1905084 --btlneckDataRate=$i --tcpModel=$model --plotX=btlneckDataRate"
    for (( i=50; i<=300; i+=50 )); do
        ./ns3 run "scratch/1905084 --btlneckDataRate=$i --tcpModel=$model --plotX=btlneckDataRate"
        gnuplot -e "set terminal png size 640,480; set xlabel 'Bottleneck DataRate (Mbps)'; set ylabel 'Throughput (Kbps)'; plot '$dir/throughput_vs_btlneckDataRate_$model.dat' using 1:2 title 'TcpNewReno' with linespoints linestyle 3, '$dir/throughput_vs_btlneckDataRate_$model.dat' using 1:3 title '$model' with linespoints linestyle 7" > "$dir/throughput_vs_btlneckDataRate__$model.png"
    done

    for (( j=60; j>=20; j-=5 )); do
        k="$j"
        i="-${k:0:1}.${k:1:1}"
        ./ns3 run "scratch/1905084 --packetLossExp=$i --tcpModel=$model --plotX=packetLossExp"
        gnuplot -e "set terminal png size 640,480; set xlabel 'Packet Loss Rate exponent'; set ylabel 'Throughput (Kbps)'; plot '$dir/throughput_vs_packetLossExp_$model.dat' using 1:2 title 'TcpNewReno' with linespoints linestyle 3, '$dir/throughput_vs_packetLossExp_$model.dat' using 1:3 title '$model' with linespoints linestyle 7" > "$dir/throughput_vs_packetLossExp__$model.png"
    done

done