/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.services.soap.client;

import static java.lang.String.format;
import static java.util.Collections.emptyList;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static org.mule.runtime.core.util.IOUtils.toDataHandler;
import org.mule.metadata.xml.XmlTypeLoader;
import org.mule.runtime.api.exception.MuleException;
import org.mule.runtime.core.util.collection.ImmutableListCollector;
import org.mule.services.soap.api.SoapVersion;
import org.mule.services.soap.api.client.MessageDispatcher;
import org.mule.services.soap.api.client.SoapClient;
import org.mule.services.soap.api.client.metadata.SoapMetadataResolver;
import org.mule.services.soap.api.exception.BadRequestException;
import org.mule.services.soap.api.exception.SoapFaultException;
import org.mule.services.soap.api.exception.SoapServiceException;
import org.mule.services.soap.api.message.SoapAttachment;
import org.mule.services.soap.api.message.SoapRequest;
import org.mule.services.soap.api.message.SoapResponse;
import org.mule.services.soap.generator.SoapRequestGenerator;
import org.mule.services.soap.generator.SoapResponseGenerator;
import org.mule.services.soap.generator.attachment.AttachmentRequestEnricher;
import org.mule.services.soap.generator.attachment.AttachmentResponseEnricher;
import org.mule.services.soap.generator.attachment.MtomRequestEnricher;
import org.mule.services.soap.generator.attachment.MtomResponseEnricher;
import org.mule.services.soap.generator.attachment.SoapAttachmentRequestEnricher;
import org.mule.services.soap.generator.attachment.SoapAttachmentResponseEnricher;
import org.mule.services.soap.introspection.WsdlIntrospecter;
import org.mule.services.soap.metadata.DefaultSoapMetadataResolver;
import org.mule.services.soap.util.XmlTransformationException;
import org.mule.services.soap.util.XmlTransformationUtils;

import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamReader;

import org.apache.commons.io.IOUtils;
import org.apache.cxf.attachment.AttachmentImpl;
import org.apache.cxf.binding.soap.SoapFault;
import org.apache.cxf.binding.soap.SoapHeader;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.message.Attachment;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.ExchangeImpl;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * a {@link SoapClient} implementation based on CXF.
 *
 * @since 4.0
 */
public class SoapCxfClient implements SoapClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(SoapCxfClient.class);

  public static final String WSC_DISPATCHER = "mule.wsc.dispatcher";
  public static final String MULE_ATTACHMENTS_KEY = "mule.wsc.attachments";
  public static final String MULE_HEADERS_KEY = "mule.wsc.headers";
  public static final String MULE_SOAP_ACTION = "mule.wsc.soap.action";
  public static final String MULE_WSC_ENCODING = "mule.wsc.encoding";
  public static final String MULE_WSC_ADDRESS = "mule.wsc.address";

  private final SoapRequestGenerator requestGenerator;
  private final SoapResponseGenerator responseGenerator;

  private final Client client;
  private final WsdlIntrospecter introspecter;
  private final XmlTypeLoader loader;
  private final MessageDispatcher dispatcher;
  private final SoapVersion version;
  private final boolean isMtom;
  private final String address;

  SoapCxfClient(Client client,
                WsdlIntrospecter introspecter,
                XmlTypeLoader typeLoader,
                MessageDispatcher dispatcher,
                SoapVersion version,
                boolean isMtom,
                String address) {

    this.client = client;
    this.introspecter = introspecter;
    this.loader = typeLoader;
    this.dispatcher = dispatcher;
    this.version = version;
    this.isMtom = isMtom;
    this.address = address;
    // TODO: MULE-10889 -> instead of creating this enrichers, interceptors that works with the live stream would be ideal
    this.requestGenerator = new SoapRequestGenerator(getRequestEnricher(isMtom), introspecter, loader);
    this.responseGenerator = new SoapResponseGenerator(getResponseEnricher(isMtom));
  }

  @Override
  public void stop() throws MuleException {
    client.destroy();
    dispatcher.dispose();
  }

  @Override
  public void start() throws MuleException {
    // do nothing
  }

  /**
   * Consumes an operation from a SOAP Web Service.
   */
  @Override
  public SoapResponse consume(SoapRequest request) throws SoapFaultException {
    List<SoapAttachment> attachments = request.getAttachments();
    String operation = request.getOperation();
    XMLStreamReader envelope;
    try {
      String xml = request.getContent() != null ? IOUtils.toString(request.getContent()) : null;
      envelope = requestGenerator.generate(operation, xml, attachments);
    } catch (IOException e) {
      throw new BadRequestException("an error occurred while parsing the provided request");
    }
    Exchange exchange = new ExchangeImpl();
    Object[] response = invoke(operation, envelope, request.getSoapHeaders(), attachments, "UTF-8", exchange);
    return responseGenerator.generate(operation, response, exchange);
  }

  @Override
  public SoapMetadataResolver getMetadataResolver() {
    return new DefaultSoapMetadataResolver(introspecter, loader);
  }


  /**
   * Invokes a Web Service Operation with the specified parameters.
   *  @param operation   the operation that is going to be invoked.
   * @param payload     the request body to be bounded in the envelope.
   * @param headers     the request headers to be bounded in the envelope.
   * @param attachments the set of attachments that aims to be sent with the request.
   * @param encoding    the encoding of the message.
   * @param exchange    the exchange instance that will carry all the parameters when intercepting the message.
   */
  public Object[] invoke(String operation, Object payload, List<org.mule.services.soap.api.message.SoapHeader> headers,
                         List<SoapAttachment> attachments,
                         String encoding, Exchange exchange) {
    try {
      BindingOperationInfo bop = getInvocationOperation();
      Map<String, Object> ctx =
          getInvocationContext(operation, encoding, transformToCxfHeaders(headers), transformToCxfAttachments(attachments));
      return client.invoke(bop, new Object[] {payload}, ctx, exchange);
    } catch (SoapFault sf) {
      throw new SoapFaultException(sf.getFaultCode(), sf.getSubCode(), parseExceptionDetail(sf.getDetail()).orElse(null),
                                   sf.getReason(),
                                   sf.getNode(), sf.getRole(),
                                   sf);
    } catch (Fault f) {
      if (f.getMessage().contains("COULD_NOT_READ_XML")) {
        throw new BadRequestException(
                                      format("Error consuming the operation [%s], the request body is not a valid XML",
                                             operation));
      }
      throw new SoapFaultException(f.getFaultCode(), parseExceptionDetail(f.getDetail()).orElse(null), f);
    } catch (Exception e) {
      throw new SoapServiceException(format("An unexpected error occur while consuming the [%s] web service operation",
                                            operation),
                                     e);
    }
  }

  private BindingOperationInfo getInvocationOperation() throws Exception {
    // Normally its not this hard to invoke the CXF Client, but we're
    // sending along some exchange properties, so we need to use a more advanced
    // method
    Endpoint ep = client.getEndpoint();
    // The operation is always named invoke because hits our ProxyService implementation.
    QName q = new QName(ep.getService().getName().getNamespaceURI(), "invoke");
    BindingOperationInfo bop = ep.getBinding().getBindingInfo().getOperation(q);
    if (bop.isUnwrappedCapable()) {
      bop = bop.getUnwrappedOperation();
    }
    return bop;
  }

  private Map<String, Object> getInvocationContext(String operation,
                                                   String encoding,
                                                   List<SoapHeader> headers,
                                                   List<Attachment> attachments) {
    Map<String, Object> props = new HashMap<>();

    if (isMtom) {
      props.put(MULE_ATTACHMENTS_KEY, attachments);
    } else {
      // is NOT mtom the attachments must not be touched by cxf, we create a custom request embedding the attachment in the xml
      props.put(MULE_ATTACHMENTS_KEY, emptyList());
    }

    props.put(MULE_WSC_ENCODING, encoding);
    props.put(MULE_HEADERS_KEY, headers);

    if (version == SoapVersion.SOAP12) {
      props.put(MULE_SOAP_ACTION, operation);
    }

    props.put(WSC_DISPATCHER, dispatcher);
    props.put(MULE_WSC_ADDRESS, address);

    Map<String, Object> ctx = new HashMap<>();
    ctx.put(Client.REQUEST_CONTEXT, props);
    return ctx;
  }

  private List<SoapHeader> transformToCxfHeaders(List<org.mule.services.soap.api.message.SoapHeader> headers) {
    if (headers == null) {
      return Collections.emptyList();
    }
    ImmutableList.Builder<SoapHeader> soapHeaders = ImmutableList.builder();
    try {
      headers.forEach(header -> {
        try {
          Element element = XmlTransformationUtils.stringToDomElement(header.getValue());
          soapHeaders.add(new SoapHeader(new QName(null, header.getId()), element));
        } catch (XmlTransformationException e) {
          throw new BadRequestException("Header [" + header.getId() + "] could not be processed correctly");
        }
      });
    } catch (Exception e) {
      throw new BadRequestException("Error while parsing the provided soap headers", e);
    }
    return soapHeaders.build();
  }

  private List<Attachment> transformToCxfAttachments(List<SoapAttachment> attachments) {
    return attachments.stream().map(a -> {
      try {
        return new AttachmentImpl(a.getId(), toDataHandler(a.getId(), a.getContent(), a.getContentType()));
      } catch (IOException e) {
        throw new BadRequestException(format("Error while preparing attachment [%s] for upload", a.getId()), e);
      }
    }).collect(new ImmutableListCollector<>());
  }

  private AttachmentRequestEnricher getRequestEnricher(boolean isMtom) {
    return isMtom ? new MtomRequestEnricher(introspecter, loader) : new SoapAttachmentRequestEnricher(introspecter, loader);
  }

  private AttachmentResponseEnricher getResponseEnricher(boolean isMtom) {
    return isMtom ? new MtomResponseEnricher(introspecter, loader) : new SoapAttachmentResponseEnricher(introspecter, loader);
  }

  private Optional<String> parseExceptionDetail(Element detail) {
    try {
      return ofNullable(XmlTransformationUtils.nodeToString(detail));
    } catch (XmlTransformationException e) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("Error while parsing Soap Exception detail: " + detail.toString(), e);
      }
      return empty();
    }
  }
}
