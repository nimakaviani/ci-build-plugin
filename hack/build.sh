#! /bin/bash

# READ BELOW TO CONFIGURE TEST PLUGIN ARTIFACT GENERATION
#
# - this script assumes that you have the aws cli installed and configured to work with your account
# - create an S3 bucket in a given aws regino
# - set the region and the bucket name for the variables REGION and BUCKET
# - create a VPC endpoint for your k8s cluster to be able to use s3
# - add a policy to your S3 bucket to allow access from the VPC using the VPC endpoint
# - configure keel to download the plugin from the location where your zipfile is uploaded to
#
# ```
# spinnaker:
#   extensibility:
#     plugins:
#       aws.CiBuildPlugin:
#         id: aws.CiBuildPlugin
#         enabled: true
#         version: 0.0.1
#     repositories:
#       awsCiBuildPluginRepo:
#         id: awsCiBuildPluginRepo
#         url: https://$BUCKET.$REGION.amazonaws.com/plugins.json
# ```

set -eux

REGION="${1:-s3-us-west-2}"
BUCKET="${2:-ci-build-plugin-bucket}"


TEMP_PLUGIN_FILE="plugins-temp.json"
PLUGIN_FILE_NAME="plugins.json"
PLUGIN_PATH="./build/distributions"
PLUGIN_FILE="$PLUGIN_PATH/$PLUGIN_FILE_NAME"
TEMP_FILE="temp.json"

version=$(cat gradle.properties | grep version | cut -d= -f2)

[ -d "$PLUGIN_PATH" ] || (echo "cannot find $PLUGIN_PATH. script needs to run in root plugin dir" && exit 1)

rm -rf $PLUGIN_PATH/*
rm -rf $TEMP_FILE,$TEMP_PLUGIN_FILE

# build the plugin
./gradlew build && ./gradlew releaseBundle

# update plugins.json
cat $PLUGIN_PATH/plugin-info.json | jq -r '.releases |= map( . + '{\"url\":\"https://$BUCKET.$REGION.amazonaws.com/ci-build-plugin-${version}.zip\"}')' > $TEMP_PLUGIN_FILE
echo [] >> $PLUGIN_FILE;
jq 'reduce inputs as $i (.; .[0] = $i)' "$PLUGIN_FILE" "$TEMP_PLUGIN_FILE" > "$TEMP_FILE";
mv $TEMP_FILE $PLUGIN_FILE

# upload artifacts to s3
aws s3 cp $PLUGIN_FILE s3://$BUCKET/$PLUGIN_FILE_NAME
aws s3 cp $PLUGIN_PATH/ci-build-plugin* s3://$BUCKET/ci-build-plugin-${version}.zip

# nuke temp files
rm -rf $TEMP_FILE
rm -rf $TEMP_PLUGIN_FILE

