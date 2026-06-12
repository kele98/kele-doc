package com.kele.doc;

import com.kele.core.buz.sys.ao.impl.SysAttachmentAOImpl;
import com.kele.core.buz.sys.model.vo.SysAttachmentVO;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;

/**
 * Unit test for simple App.
 */
public class KeleDocApplicationTest
    extends TestCase {

    /**
     * Create the test case
     *
     * @param testName name of the test case
     */
    public KeleDocApplicationTest(String testName) {
        super(testName);
    }

    /**
     * @return the suite of tests being tested
     */
    public static Test suite() {
        return new TestSuite(KeleDocApplicationTest.class);
    }

    /**
     * Rigourous Test :-)
     */
    public void testApp() {
        assertTrue(true);
    }

}
