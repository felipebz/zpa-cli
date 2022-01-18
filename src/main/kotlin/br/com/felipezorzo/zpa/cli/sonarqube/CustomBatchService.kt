package br.com.felipezorzo.zpa.cli.sonarqube

import org.sonarqube.ws.MediaTypes
import org.sonarqube.ws.client.GetRequest
import org.sonarqube.ws.client.WsConnector
import org.sonarqube.ws.client.batch.BatchService
import org.sonarqube.ws.client.batch.IssuesRequest
import java.io.InputStream

class CustomBatchService(wsConnector: WsConnector) : BatchService(wsConnector) {
    fun issuesStream(request: IssuesRequest): InputStream {
        return call(
            GetRequest(path("issues"))
                .setParam("branch", request.branch)
                .setParam("key", request.key)
                .setMediaType(MediaTypes.JSON)
        ).contentStream()
    }
}