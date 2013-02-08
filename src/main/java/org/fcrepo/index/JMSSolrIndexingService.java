package org.fcrepo.index;

import java.io.IOException;
import java.net.URI;
import java.text.ParseException;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import javax.jms.Topic;
import javax.naming.NamingException;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.kahadb.util.ByteArrayInputStream;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.springframework.beans.factory.InitializingBean;

public class JMSSolrIndexingService implements FedoraJMSService, InitializingBean {

    private ConnectionFactory jmsFac;

    private Session sess;

    private MessageConsumer consumer;

    private boolean stop = false;

    private HttpClient httpClient = new DefaultHttpClient();

    private URI solrUri = URI.create("http://localhost:8081/solr/update");

    private static Unmarshaller unmarshaller;

    private static Marshaller marshaller;

    private Connection conn;

    private Topic topic;

    // spring injected values
    private String topicName;

    private String brokerUrl;

    private String modeShapeUrl;

    public void setTopicName(String topicName) {
        this.topicName = topicName;
    }

    public void setBrokerUrl(String brokerUrl) {
        this.brokerUrl = brokerUrl;
    }

    public void setModeShapeUrl(String modeShapeUrl) {
        this.modeShapeUrl = modeShapeUrl;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (topicName == null) {
            throw new IllegalArgumentException("Topic name can not be null. Please check your spring configuration");
        }
        if (brokerUrl == null) {
            throw new IllegalArgumentException("Broker url can not be null. Please check your spring configuration");
        }
        if (modeShapeUrl == null) {
            throw new IllegalArgumentException("Nodeshape url can not be null. Please check your spring configuration");
        }
    }

    public boolean startService() {
        boolean initSuccess = false;
        try {
            initSuccess = initService();
        } catch (NamingException e) {
            e.printStackTrace();
        } catch (JMSException e) {
            e.printStackTrace();
        } catch (JAXBException e) {
            e.printStackTrace();
        }
        while (initSuccess && !stop) {
            try {
                TextMessage msg = (TextMessage) consumer.receive();
                String xml = msg.getText();
                // get the id from the message in order to fetch more metadata
                // from the repo
                Entry e = (Entry) unmarshaller.unmarshal(new ByteArrayInputStream(xml.getBytes()));
                ObjectProfile profile = fetchProfile(e.category.term);
                addToSolr(profile);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return initSuccess;
    }

    private ObjectProfile fetchProfile(String id) throws IOException, IllegalStateException, JAXBException {
        HttpGet get = new HttpGet(modeShapeUrl + "/rest/objects/" + id);
        System.out.println("fetching from URL: " + get.getURI().toASCIIString());
        HttpResponse resp = httpClient.execute(get);
        String profile = IOUtils.toString(resp.getEntity().getContent());
        System.out.println("profile: " + profile);
        return (ObjectProfile) unmarshaller.unmarshal(new ByteArrayInputStream(profile.getBytes()));
    }

    private void addToSolr(ObjectProfile profile) throws ClientProtocolException, IOException, ParseException {
        // translate lastModDate to UTC for Solr
        DateTimeFormatter df = ISODateTimeFormat.dateTime();
        DateTime dt = df.parseDateTime(profile.objLastModDate);
        DateTime dtUtc = new DateTime(dt.getMillis(), DateTimeZone.UTC);
        // create the XML request
        StringBuilder xmlBuilder = new StringBuilder("<add><doc>");
        xmlBuilder.append("<field name=\"id\">" + profile.pid + "</field>");
        xmlBuilder.append("<field name=\"title\">" + profile.objLabel + "</field>");
        xmlBuilder.append("<field name=\"author\">" + profile.objOwnerId + "</field>");
        xmlBuilder.append("<field name=\"last_modified\">" + df.print(dtUtc) + "</field>");
        xmlBuilder.append("</doc></add>");
        String doc = xmlBuilder.toString();
        HttpPost post = new HttpPost(solrUri);
        post.setEntity(new StringEntity(doc, ContentType.APPLICATION_XML));
        System.out.println(doc);
        HttpResponse resp = httpClient.execute(post);
        if (resp.getStatusLine().getStatusCode() != 200) {
            System.err.println("Unable to post document to Solr");
            System.err.println("Solr says: ");
            System.err.println(resp.getStatusLine().getStatusCode() + ": " + resp.getStatusLine().getReasonPhrase());
        }
    }

    private boolean initService() throws NamingException, JMSException, JAXBException {
        jmsFac = new ActiveMQConnectionFactory(brokerUrl);
        try {
            conn = jmsFac.createConnection();
        } catch (JMSException e) {
            System.err.println("Unable to connect to broker service at " + brokerUrl);
            System.err.println("Exiting " + this.getClass().getName() + "...");
            return false;
        }
        conn.start();
        sess = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        topic = sess.createTopic(topicName);
        consumer = sess.createConsumer(topic);
        JAXBContext jaxbCtx = JAXBContext.newInstance(Entry.class, ObjectProfile.class);
        unmarshaller = jaxbCtx.createUnmarshaller();
        marshaller = jaxbCtx.createMarshaller();
        marshaller.setProperty("com.sun.xml.bind.namespacePrefixMapper", new JMSClientNameSpacePrefixMapper());
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        return true;
    }

    public void stopService() {
        this.stop = true;
    }
}