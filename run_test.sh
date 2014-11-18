#!/bin/bash

WORKSPACE="workspace/"

#delete workspace
rm -rf workspace

echo "Which directory do you want to check for integrity?"
read CHECK_DIR

echo "Enter the size of the test file (MB)"
read FILE_SIZE_MB

echo "Checking $CHECK_DIR with $FILE_SIZE_MB MB of random data"

#create workspace if it does not yet exist
mkdir workspace


#create random file that we will run comparisons against
echo "Generating file with a file size of 1 MB from /dev/urandom..."

dd if=/dev/urandom of=$WORKSPACE/compare_internal_base bs=1000000 count=1

$CORRECT_FILE_CREATION

#cat file into the internal file FILE_SIZE_MB times.
 COUNTER=0
         while [  $COUNTER -lt $FILE_SIZE_MB ]; do
         	if [ $COUNTER%100==0 ]; then
                echo "$COUNTER MB generated"
			 fi
         	 cat $WORKSPACE/compare_internal_base >> $WORKSPACE/compare_internal 
             let COUNTER=COUNTER+1 
         done


echo "Generating faulty compare file.."

dd if=/dev/urandom of=$WORKSPACE/compare_internal_faulty bs=1048576 count=1

$FAULTY_FILE_CREATION

#copy the file to the device
echo "Copying GOOD and FAULTY file file to USB...."
cp $WORKSPACE/compare_internal $CHECK_DIR/compare_external
cp $WORKSPACE/compare_internal_faulty $CHECK_DIR/compare_external_faulty

#compare internal with external file
echo "Starting integrity 1 by comparing local file with external file"
cmp --silent $WORKSPACE/compare_internal $CHECK_DIR/compare_external && echo "SUCCESS: Compare file was the same, integrity 1 success 1/2" || echo "FAIL: files that should be equal are different, integrity 1 failed 1/2"
cmp --silent $WORKSPACE/compare_internal $CHECK_DIR/compare_external_faulty && echo "FAIL: FAULTY Compare file was the same, failcheck for integrity 1 fail" || echo "SUCCESS: FAULTY Compare file was NOT the same, integrity 1 success 2/2"

echo "Moving external file back to workspace"

#move the file back to the workspace
mv $CHECK_DIR/compare_external workspace
mv $CHECK_DIR/compare_external_faulty workspace

#check if contents are still equal
echo "Starting integrity 2 by comparing local files"
cmp --silent $WORKSPACE/compare_internal $WORKSPACE/compare_external && echo "SUCCESS: Compare file was the same, integrity 2 success 1/2" || echo "FAIL: files that should be equal are different, integrity 2 failed 1/2"
cmp --silent $WORKSPACE/compare_internal $WORKSPACE/compare_external_faulty && echo "FAIL: FAULTY Compare file was the same, failcheck for integrity 2 fail" || echo "SUCCESS: FAULTY Compare file was NOT the same, integrity 2 success 2/2"