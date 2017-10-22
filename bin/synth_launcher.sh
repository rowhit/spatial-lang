#!/bin/bash

# This script sits in /home/mattfel/regression/synth

#1 = backend

export LANG=en_US.UTF-8
export JAVA_HOME=$(readlink -f $(dirname $(readlink -f $(which java)))/..)
if [[ ${JAVA_HOME} = *"/jre"* ]]; then # ugly ass hack because idk wtf is going on with tucson
  export JAVA_HOME=${JAVA_HOME}/..
fi
export XILINX_VIVADO=/opt/Xilinx/Vivado/2017.1
export PATH=/usr/bin:/local/ssd/home/mattfel/aws-fpga/hdk/common/scripts:/opt/Xilinx/SDx/2017.1/Vivado/bin:/opt/Xilinx/SDx/2017.1/SDK/bin:$PATH
export FOREGROUND="-foreground"
#cd /home/mattfel/aws-fpga/
source /home/mattfel/aws-fpga/hdk_setup.sh
export LD_LIBRARY_PATH=${XILINX_VIVADO}/lib/lnx64.o:${LD_LIBRARY_PATH}
export AWS_HOME=/home/mattfel/aws-fpga
export AWS_CONFIG_FILE=/home/mattfel/aws-fpga/hdk/cl/examples/rootkey.csv
export RPT_HOME=/home/mattfel/aws-fpga/hdk/cl/examples

this_machine=`hostname`
export SBT_OPTS="-Xmx64G -Xss1G"
export _JAVA_OPTIONS="-Xmx32g -Xss8912k -Xms16g"
export REGRESSION_HOME="/home/mattfel/regression/synth/$1"

export SPATIAL_HOME=${REGRESSION_HOME}/spatial/spatial-lang

rm ${REGRESSION_HOME}/protocol/done
mkdir ${REGRESSION_HOME}/next-spatial
cd ${REGRESSION_HOME}/next-spatial
git clone git@github.com:stanford-ppl/spatial-lang
cd spatial-lang
git submodule update --init
export hash=`git rev-parse HEAD`
export timestamp=`git show -s --format=%ci`
echo $hash > ${REGRESSION_HOME}/data/hash
echo $timestamp > ${REGRESSION_HOME}/data/timestamp

# Run tests
cd ${REGRESSION_HOME}/next-spatial/spatial-lang
bash bin/synth_regression.sh $1

# Kickstart next test
touch ${REGRESSION_HOME}/protocol/done
