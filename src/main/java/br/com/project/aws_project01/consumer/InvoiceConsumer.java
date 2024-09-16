package br.com.project.aws_project01.consumer;

import br.com.project.aws_project01.model.Invoice;
import br.com.project.aws_project01.model.SnsMessage;
import br.com.project.aws_project01.repository.InvoiceRepository;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.event.S3EventNotification;
import com.amazonaws.services.s3.model.S3Object;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.JMSException;
import jakarta.jms.TextMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

@Service
public class InvoiceConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(InvoiceConsumer.class);
    private ObjectMapper objectMapper;
    private InvoiceRepository invoiceRepository;
    private AmazonS3 amazonS3;

    @Autowired
    public InvoiceConsumer(ObjectMapper objectMapper, InvoiceRepository invoiceRepository, AmazonS3 amazonS3){
        this.objectMapper = objectMapper;
        this.invoiceRepository = invoiceRepository;
        this.amazonS3 = amazonS3;
    }

    @JmsListener(destination = "${aws.sqs.queue.invoice.events.name}")
    public void receiveS3Event(TextMessage textMessage) throws JMSException, IOException{
        SnsMessage snsMessage = objectMapper.readValue(textMessage.getText(), SnsMessage.class);

        S3EventNotification s3EventNotification = objectMapper.readValue(snsMessage.getMessage(), S3EventNotification.class);

        processInvoiceNotification(s3EventNotification);
    }

    private void processInvoiceNotification(S3EventNotification s3EventNotification) throws IOException {
        // Verifica se o evento contém registros
        if (s3EventNotification == null || s3EventNotification.getRecords() == null || s3EventNotification.getRecords().isEmpty()) {
            LOG.warn("S3EventNotification is null or does not contain any records.");
            return; // Sai do método se não houver registros
        }

        // Itera sobre os registros válidos
        for (S3EventNotification.S3EventNotificationRecord s3EventNotificationRecord : s3EventNotification.getRecords()) {
            S3EventNotification.S3Entity s3Entity = s3EventNotificationRecord.getS3();

            // Obtém o nome do bucket e o objeto da notificação
            String bucketName = s3Entity.getBucket().getName();
            String objectKey = s3Entity.getObject().getKey();

            try {
                // Faz o download do objeto S3 e converte o JSON para a classe Invoice
                String invoiceFile = downloadObject(bucketName, objectKey);
                Invoice invoice = objectMapper.readValue(invoiceFile, Invoice.class);
                LOG.info("Invoice received: {}", invoice.getInvoiceNumber());

                // Salva a invoice no banco de dados
                invoiceRepository.save(invoice);

                // Deleta o objeto do bucket S3 após o processamento
                amazonS3.deleteObject(bucketName, objectKey);
            } catch (IOException e) {
                LOG.error("Error processing invoice from S3 object: {}/{}", bucketName, objectKey, e);
                // Tratamento adicional de erro ou lançamento de exceção, se necessário
            }
        }
    }


    private String downloadObject(String bucketName, String objectKey) throws IOException {
        S3Object s3Object = amazonS3.getObject(bucketName,objectKey);
        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader(s3Object.getObjectContent())
        );
        String content;
        while ((content = bufferedReader.readLine()) != null){
            stringBuilder.append(content);
        }
        return stringBuilder.toString();
    }
}
