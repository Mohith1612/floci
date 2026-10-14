package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class S3MultipartObjectSizeIntegrationTest {

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "9223372036854775808"})
    void malformedSizeHeaderReturnsClientError(String expectedSize) {
        String bucket = "multipart-size-" + UUID.randomUUID();
        String path = "/" + bucket + "/object";
        given().put("/" + bucket).then().statusCode(200);
        String uploadId = null;
        try {
            uploadId = given().post(path + "?uploads").then().statusCode(200)
                    .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
            String eTag = given().queryParam("uploadId", uploadId).queryParam("partNumber", 1).body("data")
                    .put(path).then().statusCode(200).extract().header("ETag");
            String completion = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"
                    + eTag + "</ETag></Part></CompleteMultipartUpload>";
            given().queryParam("uploadId", uploadId).header("x-amz-mp-object-size", expectedSize)
                    .body(completion).post(path).then().statusCode(400)
                    .body("Error.Code", equalTo("InvalidArgument"));
        } finally {
            if (uploadId != null) {
                given().queryParam("uploadId", uploadId).delete(path);
            }
            given().delete("/" + bucket);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 3, 5, 4294967296L})
    void mismatchedSizeRejectsCompletionAndPreservesUploadForRetry(long expectedSize) {
        String bucket = "multipart-size-" + UUID.randomUUID();
        String path = "/" + bucket + "/object";
        given().put("/" + bucket).then().statusCode(200);
        try {
            String uploadId = given().post(path + "?uploads").then().statusCode(200)
                    .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
            String eTag = given().queryParam("uploadId", uploadId).queryParam("partNumber", 1).body("data")
                    .put(path).then().statusCode(200).extract().header("ETag");
            String completion = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"
                    + eTag + "</ETag></Part></CompleteMultipartUpload>";
            given().queryParam("uploadId", uploadId).header("x-amz-mp-object-size", expectedSize)
                    .body(completion).post(path).then().statusCode(400)
                    .body("Error.Code", equalTo("InvalidRequest"))
                    .body("Error.Message", equalTo("The provided 'x-amz-mp-object-size' header value "
                            + expectedSize + " does not match what was computed: 4"));
            given().head(path).then().statusCode(404);
            given().queryParam("uploadId", uploadId).get(path).then().statusCode(200)
                    .body(containsString("<PartNumber>1</PartNumber>"));
            given().queryParam("uploadId", uploadId).header("x-amz-mp-object-size", 4)
                    .body(completion).post(path).then().statusCode(200);
            given().get(path).then().statusCode(200).body(equalTo("data"));
        } finally {
            given().delete(path);
            given().delete("/" + bucket);
        }
    }
}
