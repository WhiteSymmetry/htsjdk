/*
 * The MIT License
 *
 * Copyright (c) 2009 The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package net.sf.picard.sam;

import net.sf.picard.PicardException;
import net.sf.picard.cmdline.CommandLineProgram;
import net.sf.picard.cmdline.Option;
import net.sf.picard.cmdline.StandardOptionDefinitions;
import net.sf.picard.cmdline.Usage;
import net.sf.picard.fastq.FastqRecord;
import net.sf.picard.fastq.FastqWriter;
import net.sf.picard.io.IoUtil;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMRecord;
import net.sf.samtools.util.SequenceUtil;
import net.sf.samtools.util.StringUtil;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Extracts read sequences and qualities from the input SAM/BAM file and writes them into 
 * the output file in Sanger fastq format. 
 * See <a href="http://maq.sourceforge.net/fastq.shtml">MAQ FastQ specification</a> for details.
 * In the RC mode (default is True), if the read is aligned and the alignment is to the reverse strand on the genome,
 * the read's sequence from input sam file will be reverse-complemented prior to writing it to fastq in order restore correctly 
 * the original read sequence as it was generated by the sequencer.
 */
public class BamToFastq extends CommandLineProgram {
    @Usage(programVersion="1.0") 
    public String USAGE = "Extracts read sequences and qualities from the input SAM/BAM file and writes them into "+
        "the output file in Sanger fastq format. In the RC mode (default is True), if the read is aligned and the alignment is to the reverse strand on the genome, "+
        "the read's sequence from input SAM file will be reverse-complemented prior to writing it to fastq in order restore correctly "+
        "the original read sequence as it was generated by the sequencer.";

    @Option(doc="Input SAM/BAM file to extract reads from", shortName=StandardOptionDefinitions.INPUT_SHORT_NAME)
    public File INPUT ;

    @Option(shortName="F", doc="Output fastq file (single-end fastq or, if paired, first end of the pair fastq).")
    public File FASTQ ;

    @Option(shortName="F2", doc="Output fastq file (if paired, second end of the pair fastq).", optional=true)
    public File SECOND_END_FASTQ ;


    @Option(shortName="RC", doc="Re-reverse bases and qualities of reads aligned to the negative strand before writing them to fastq", optional=true)
    public Boolean RE_REVERSE = true;

    public static void main(final String[] argv) {
        System.exit(new BamToFastq().instanceMain(argv));
    }

    protected int doWork() {
        if (SECOND_END_FASTQ == null) {
            doUnpaired();
        }
        else {
            doPaired();
        }
        return 0;
    }

    protected void doUnpaired() {
        IoUtil.assertFileIsReadable(INPUT);
        IoUtil.assertFileIsWritable(FASTQ);

        final SAMFileReader reader = new SAMFileReader(IoUtil.openFileForReading(INPUT));
        final FastqWriter writer = new FastqWriter(FASTQ);

        for (final SAMRecord record : reader ) {
            writeRecord(record, null, writer);
        }
        reader.close();
        writer.close();
    }

    protected void doPaired() {
        IoUtil.assertFileIsReadable(INPUT);
        IoUtil.assertFileIsWritable(FASTQ);
        IoUtil.assertFileIsWritable(SECOND_END_FASTQ);

        final SAMFileReader reader = new SAMFileReader(IoUtil.openFileForReading(INPUT));
        final FastqWriter writer1 = new FastqWriter(FASTQ);
        final FastqWriter writer2 = new FastqWriter(SECOND_END_FASTQ);

        final Map<String,SAMRecord> firstSeenMates = new HashMap<String,SAMRecord>();

        for (final SAMRecord currentRecord : reader ) {
            final String currentReadName = currentRecord.getReadName() ;
            final SAMRecord firstRecord = firstSeenMates.get(currentReadName);
            if (firstRecord == null) {
                firstSeenMates.put(currentReadName, currentRecord) ;
            }
            else {
                assertPairedMates(firstRecord, currentRecord);

                if (currentRecord.getFirstOfPairFlag()) {
                     writeRecord(currentRecord, 1, writer1);
                     writeRecord(firstRecord, 2, writer2);
                }
                else {
                     writeRecord(firstRecord, 1, writer1);
                     writeRecord(currentRecord, 2, writer2);
                }
                firstSeenMates.remove(currentReadName);
            }
        }

        if (firstSeenMates.size() > 0) {
            throw new PicardException("Found "+firstSeenMates.size()+" unpaired mates");
        }
  
        reader.close();
        writer1.close();
        writer2.close();
    }

    void writeRecord(final SAMRecord read, final Integer mateNumber, final FastqWriter writer) {
        final String seqHeader = mateNumber==null ? read.getReadName() : read.getReadName() + "/"+ mateNumber;
        String readString = read.getReadString();
        String baseQualities = read.getBaseQualityString();
        if ( !read.getReadUnmappedFlag() && RE_REVERSE && read.getReadNegativeStrandFlag() ) {
            readString = SequenceUtil.reverseComplement(read.getReadString());
            baseQualities = StringUtil.reverseString(read.getBaseQualityString());
        }
        writer.write(new FastqRecord(seqHeader, readString, "", baseQualities));

    }

    private void assertPairedMates(final SAMRecord record1, final SAMRecord record2) {
        if (! (record1.getFirstOfPairFlag() && record2.getSecondOfPairFlag() ||
               record2.getFirstOfPairFlag() && record1.getSecondOfPairFlag() ) ) {
            throw new PicardException("Illegal mate state");
        }
    }
}