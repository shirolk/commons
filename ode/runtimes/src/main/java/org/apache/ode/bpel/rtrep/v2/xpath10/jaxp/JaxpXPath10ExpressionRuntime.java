/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.ode.bpel.rtrep.v2.xpath10.jaxp;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.transform.TransformerFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.ode.bpel.common.FaultException;
import org.apache.ode.bpel.rtrep.common.ConfigurationException;
import org.apache.ode.bpel.rtrep.v2.EvaluationContext;
import org.apache.ode.bpel.rtrep.v2.ExpressionLanguageRuntime;
import org.apache.ode.bpel.rtrep.v2.OExpression;
import org.apache.ode.bpel.rtrep.v2.xpath10.OXPath10Expression;
import org.apache.ode.utils.DOMUtils;
import org.apache.ode.utils.ISO8601DateParser;
import org.apache.ode.utils.xsd.Duration;
import org.apache.ode.utils.xsl.XslTransformHandler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

/**
 * JAXP based XPath 1.0 Expression Language run-time subsytem.
 */
public class JaxpXPath10ExpressionRuntime implements ExpressionLanguageRuntime {
    /** Class-level logger. */
    private static final Log __log = LogFactory.getLog(JaxpXPath10ExpressionRuntime.class);

    public void initialize(Map properties) throws ConfigurationException {
        TransformerFactory trsf = TransformerFactory.newInstance();
        __log.debug("JAXP runtime: TransformerFactory impl = " + trsf.getClass());
        XslTransformHandler.getInstance().setTransformerFactory(trsf);
    }

    public String evaluateAsString(OExpression cexp, EvaluationContext ctx) throws FaultException {
        return (String) evaluate(cexp, ctx, XPathConstants.STRING);
    }

    public boolean evaluateAsBoolean(OExpression cexp, EvaluationContext ctx) throws FaultException {
        return (Boolean) evaluate(cexp, ctx, XPathConstants.BOOLEAN);
    }

    public Number evaluateAsNumber(OExpression cexp, EvaluationContext ctx) throws FaultException {
        return (Number) evaluate(cexp, ctx, XPathConstants.NUMBER);
    }

    public List evaluate(OExpression cexp, EvaluationContext ctx) throws FaultException {
        List result;
        Object someRes = null;
        try {
            someRes = evaluate(cexp, ctx, XPathConstants.NODESET);
        } catch (FaultException ex) {
            try {
                // JDK implementation of evaluation seems to be more strict about result types than Saxon:
                // basic return types are not converted to lists automatically.
                // this simulates Saxon behaviour: get a string result and put it in a list.
                List resultList = new ArrayList(1);
                resultList.add(evaluateAsString(cexp, ctx));
                someRes = resultList;
            } catch (Exception ex2) {
                // re-throw original exception if workaround does not work.
                throw ex;
            }
        }
        if (someRes instanceof List) {
            result = (List) someRes;
            __log.debug("Returned list of size " + result.size());
            if ((result.size() == 1) && !(result.get(0) instanceof Node)) {
                // Dealing with a Java class
                Object simpleType = result.get(0);
                // Dates get a separate treatment as we don't want to call toString on them
                String textVal;
                if (simpleType instanceof Date)
                    textVal = ISO8601DateParser.format((Date) simpleType);
                else
                    textVal = simpleType.toString();

                // Wrapping in a document
                Document d = DOMUtils.newDocument();
                // Giving our node a parent just in case it's an LValue expression
                Element wrapper = d.createElement("wrapper");
                Text text = d.createTextNode(textVal);
                wrapper.appendChild(text);
                d.appendChild(wrapper);
                result = Collections.singletonList(text);
            }
        } else if (someRes instanceof NodeList) {
            NodeList retVal = (NodeList) someRes;
            __log.debug("Returned node list of size " + retVal.getLength());
            result = new ArrayList(retVal.getLength());
            for (int m = 0; m < retVal.getLength(); ++m) {
                Node val = retVal.item(m);
                if (val.getNodeType() == Node.DOCUMENT_NODE)
                    val = ((Document) val).getDocumentElement();
                result.add(val);
            }
        } else {
            result = null;
        }

        return result;
    }

    public Node evaluateNode(OExpression cexp, EvaluationContext ctx) throws FaultException {
        List retVal = evaluate(cexp, ctx);
        if (retVal.size() == 0)
            throw new FaultException(cexp.getOwner().constants.qnSelectionFailure, "No results for expression: "
                + cexp);
        if (retVal.size() > 1)
            throw new FaultException(cexp.getOwner().constants.qnSelectionFailure,
                "Multiple results for expression: " + cexp);
        return (Node) retVal.get(0);
    }

    public Calendar evaluateAsDate(OExpression cexp, EvaluationContext context) throws FaultException {
        List literal = DOMUtils.toList(evaluate(cexp, context, XPathConstants.NODESET));
        if (literal.size() == 0)
            throw new FaultException(cexp.getOwner().constants.qnSelectionFailure, "No results for expression: "
                + cexp);
        if (literal.size() > 1)
            throw new FaultException(cexp.getOwner().constants.qnSelectionFailure,
                "Multiple results for expression: " + cexp);

        Object date = literal.get(0);
        if (date instanceof Calendar)
            return (Calendar) date;
        if (date instanceof Date) {
            Calendar cal = Calendar.getInstance();
            cal.setTime((Date) date);
            return cal;
        }
        if (date instanceof Element)
            date = ((Element) date).getTextContent();

        try {
            return ISO8601DateParser.parseCal(date.toString());
        } catch (Exception ex) {
            String errmsg = "Invalid date format: " + literal;
            __log.error(errmsg);
            throw new FaultException(cexp.getOwner().constants.qnInvalidExpressionValue, errmsg);
        }
    }

    public Duration evaluateAsDuration(OExpression cexp, EvaluationContext context) throws FaultException {
        String literal = this.evaluateAsString(cexp, context);
        try {
            return new Duration(literal);
        } catch (Exception ex) {
            String errmsg = "Invalid duration: " + literal;
            __log.error(errmsg, ex);
            throw new FaultException(cexp.getOwner().constants.qnInvalidExpressionValue, errmsg);
        }
    }

    private Object evaluate(OExpression cexp, EvaluationContext ctx, QName type) throws FaultException {
        try {
            OXPath10Expression oxpath = (OXPath10Expression) cexp;
            __log.debug("JAXP runtime: evaluating " + oxpath.xpath);
            // use default XPath implementation
            XPathFactory xpf = XPathFactory.newInstance();
            __log.debug("JAXP runtime: XPathFactory impl = " + xpf.getClass());
            XPath xpe = xpf.newXPath();
            xpe.setXPathFunctionResolver(new JaxpFunctionResolver(ctx, oxpath));
            xpe.setXPathVariableResolver(new JaxpVariableResolver(ctx, oxpath));
            xpe.setNamespaceContext(oxpath.namespaceCtx);
            XPathExpression expr = xpe.compile(((OXPath10Expression) cexp).xpath);
            Object evalResult =
                expr.evaluate(ctx.getRootNode() == null ? DOMUtils.newDocument() : ctx.getRootNode(), type);
            if (evalResult != null && __log.isDebugEnabled()) {
                __log.debug("Expression " + cexp.toString() + " generated result " + evalResult + " - type="
                    + evalResult.getClass().getName());
                if (ctx.getRootNode() != null)
                    __log.debug("Was using context node " + DOMUtils.domToString(ctx.getRootNode()));
            }
            return evalResult;
        } catch (XPathExpressionException e) {
            // Extracting the real cause from all this wrapping isn't a simple task
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new FaultException(cexp.getOwner().constants.qnSubLanguageExecutionFault, cause.getMessage(),
                cause);
        } catch (WrappedFaultException wre) {
            __log.debug("Could not evaluate expression because of ", wre);
            throw (FaultException) wre.getCause();
        } catch (Throwable t) {
            __log.debug("Could not evaluate expression because of ", t);
            throw new FaultException(cexp.getOwner().constants.qnSubLanguageExecutionFault, t.getMessage(), t);
        }

    }
}
