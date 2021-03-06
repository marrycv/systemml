/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.instructions.spark;

import java.util.ArrayList;
import java.util.Iterator;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.PairFlatMapFunction;
import org.apache.spark.api.java.function.PairFunction;

import scala.Tuple2;

import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.controlprogram.context.SparkExecutionContext;
import org.apache.sysml.runtime.functionobjects.Builtin;
import org.apache.sysml.runtime.functionobjects.Multiply;
import org.apache.sysml.runtime.functionobjects.Plus;
import org.apache.sysml.runtime.functionobjects.PlusMultiply;
import org.apache.sysml.runtime.instructions.InstructionUtils;
import org.apache.sysml.runtime.instructions.cp.CPOperand;
import org.apache.sysml.runtime.instructions.spark.data.PartitionedBroadcast;
import org.apache.sysml.runtime.matrix.MatrixCharacteristics;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.MatrixIndexes;
import org.apache.sysml.runtime.matrix.operators.BinaryOperator;
import org.apache.sysml.runtime.matrix.operators.Operator;
import org.apache.sysml.runtime.matrix.operators.UnaryOperator;
import org.apache.sysml.runtime.util.UtilFunctions;

public class CumulativeOffsetSPInstruction extends BinarySPInstruction {
	private BinaryOperator _bop = null;
	private UnaryOperator _uop = null;
	private final double _initValue ;
	private final boolean _broadcast;

	private CumulativeOffsetSPInstruction(Operator op, CPOperand in1, CPOperand in2, CPOperand out, double init, boolean broadcast, String opcode, String istr) {
		super(SPType.CumsumOffset, op, in1, in2, out, opcode, istr);

		if ("bcumoffk+".equals(opcode)) {
			_bop = new BinaryOperator(Plus.getPlusFnObject());
			_uop = new UnaryOperator(Builtin.getBuiltinFnObject("ucumk+"));
		}
		else if ("bcumoff*".equals(opcode)) {
			_bop = new BinaryOperator(Multiply.getMultiplyFnObject());
			_uop = new UnaryOperator(Builtin.getBuiltinFnObject("ucum*"));
		}
		else if ("bcumoff+*".equals(opcode)) {
			_bop = new BinaryOperator(PlusMultiply.getFnObject());
			_uop = new UnaryOperator(Builtin.getBuiltinFnObject("ucumk+*"));
		}
		else if ("bcumoffmin".equals(opcode)) {
			_bop = new BinaryOperator(Builtin.getBuiltinFnObject("min"));
			_uop = new UnaryOperator(Builtin.getBuiltinFnObject("ucummin"));
		}
		else if ("bcumoffmax".equals(opcode)) {
			_bop = new BinaryOperator(Builtin.getBuiltinFnObject("max"));
			_uop = new UnaryOperator(Builtin.getBuiltinFnObject("ucummax"));
		}

		_initValue = init;
		_broadcast = broadcast;
	}

	public static CumulativeOffsetSPInstruction parseInstruction ( String str ) {
		String[] parts = InstructionUtils.getInstructionPartsWithValueType( str );
		InstructionUtils.checkNumFields(parts, 5);
		String opcode = parts[0];
		CPOperand in1 = new CPOperand(parts[1]);
		CPOperand in2 = new CPOperand(parts[2]);
		CPOperand out = new CPOperand(parts[3]);
		double init = Double.parseDouble(parts[4]);
		boolean broadcast = Boolean.parseBoolean(parts[5]);
		return new CumulativeOffsetSPInstruction(null, in1, in2, out, init, broadcast, opcode, str);
	}

	@Override
	public void processInstruction(ExecutionContext ec) {
		SparkExecutionContext sec = (SparkExecutionContext)ec;
		MatrixCharacteristics mc1 = sec.getMatrixCharacteristics(input1.getName());
		MatrixCharacteristics mc2 = sec.getMatrixCharacteristics(input2.getName());
		long rlen = mc2.getRows();
		int brlen = mc2.getRowsPerBlock();
		
		//get and join inputs
		JavaPairRDD<MatrixIndexes,MatrixBlock> inData = sec.getBinaryBlockRDDHandleForVariable(input1.getName());
		JavaPairRDD<MatrixIndexes,Tuple2<MatrixBlock,MatrixBlock>> joined = null;
		
		if( _broadcast ) {
			//broadcast offsets and broadcast join with data
			PartitionedBroadcast<MatrixBlock> inAgg = sec.getBroadcastForVariable(input2.getName());
			joined = inData.mapToPair(new RDDCumSplitLookupFunction(inAgg,_initValue, rlen, brlen));
		}
		else {
			//prepare aggregates (cumsplit of offsets) and repartition join with data
			joined = inData.join(sec
				.getBinaryBlockRDDHandleForVariable(input2.getName())
				.flatMapToPair(new RDDCumSplitFunction(_initValue, rlen, brlen)));
		}
		
		//execute cumulative offset (apply cumulative op w/ offsets)
		JavaPairRDD<MatrixIndexes,MatrixBlock> out = joined
			.mapValues(new RDDCumOffsetFunction(_uop, _bop));
		
		//put output handle in symbol table
		if( _bop.fn instanceof PlusMultiply )
			sec.getMatrixCharacteristics(output.getName())
				.set(mc1.getRows(), 1, mc1.getRowsPerBlock(), mc1.getColsPerBlock());
		else //general case
			updateUnaryOutputMatrixCharacteristics(sec);
		sec.setRDDHandleForVariable(output.getName(), out);
		sec.addLineageRDD(output.getName(), input1.getName());
		sec.addLineage(output.getName(), input2.getName(), _broadcast);
	}

	private static class RDDCumSplitFunction implements PairFlatMapFunction<Tuple2<MatrixIndexes, MatrixBlock>, MatrixIndexes, MatrixBlock> 
	{
		private static final long serialVersionUID = -8407407527406576965L;
		
		private double _initValue = 0;
		private int _brlen = -1;
		private long _lastRowBlockIndex;
		
		public RDDCumSplitFunction( double initValue, long rlen, int brlen )
		{
			_initValue = initValue;
			_brlen = brlen;
			_lastRowBlockIndex = (long)Math.ceil((double)rlen/brlen);
		}
		
		@Override
		public Iterator<Tuple2<MatrixIndexes, MatrixBlock>> call( Tuple2<MatrixIndexes, MatrixBlock> arg0 ) 
			throws Exception 
		{
			ArrayList<Tuple2<MatrixIndexes, MatrixBlock>> ret = new ArrayList<>();
			
			MatrixIndexes ixIn = arg0._1();
			MatrixBlock blkIn = arg0._2();
			
			long rixOffset = (ixIn.getRowIndex()-1)*_brlen;
			boolean firstBlk = (ixIn.getRowIndex() == 1);
			boolean lastBlk = (ixIn.getRowIndex() == _lastRowBlockIndex );
			
			//introduce offsets w/ init value for first row 
			if( firstBlk ) { 
				MatrixIndexes tmpix = new MatrixIndexes(1, ixIn.getColumnIndex());
				MatrixBlock tmpblk = new MatrixBlock(1, blkIn.getNumColumns(), blkIn.isInSparseFormat());
				if( _initValue != 0 ){
					for( int j=0; j<blkIn.getNumColumns(); j++ )
						tmpblk.appendValue(0, j, _initValue);
				}
				ret.add(new Tuple2<>(tmpix, tmpblk));
			}
			
			//output splitting (shift by one), preaggregated offset used by subsequent block
			for( int i=0; i<blkIn.getNumRows(); i++ )
				if( !(lastBlk && i==(blkIn.getNumRows()-1)) ) //ignore last row
				{
					MatrixIndexes tmpix = new MatrixIndexes(rixOffset+i+2, ixIn.getColumnIndex());
					MatrixBlock tmpblk = new MatrixBlock(1, blkIn.getNumColumns(), blkIn.isInSparseFormat());
					blkIn.slice(i, i, 0, blkIn.getNumColumns()-1, tmpblk);
					ret.add(new Tuple2<>(tmpix, tmpblk));
				}
			
			return ret.iterator();
		}
	}
	
	private static class RDDCumSplitLookupFunction implements PairFunction<Tuple2<MatrixIndexes, MatrixBlock>, MatrixIndexes, Tuple2<MatrixBlock,MatrixBlock>> 
	{
		private static final long serialVersionUID = -2785629043886477479L;
		
		private final PartitionedBroadcast<MatrixBlock> _pbc;
		private final double _initValue;
		private final int _brlen;
		
		public RDDCumSplitLookupFunction(PartitionedBroadcast<MatrixBlock> pbc, double initValue, long rlen, int brlen) {
			_pbc = pbc;
			_initValue = initValue;
			_brlen = brlen;
		}
		
		@Override
		public Tuple2<MatrixIndexes, Tuple2<MatrixBlock,MatrixBlock>> call(Tuple2<MatrixIndexes, MatrixBlock> arg0) throws Exception {
			MatrixIndexes ixIn = arg0._1();
			MatrixBlock blkIn = arg0._2();
			
			//compute block and row indexes
			long brix = UtilFunctions.computeBlockIndex(ixIn.getRowIndex()-1, _brlen);
			int rix = UtilFunctions.computeCellInBlock(ixIn.getRowIndex()-1, _brlen);
			
			//lookup offset row and return joined output
			MatrixBlock off = (ixIn.getRowIndex() == 1) ? new MatrixBlock(1, blkIn.getNumColumns(), _initValue) :
				_pbc.getBlock((int)brix, (int)ixIn.getColumnIndex()).slice(rix, rix);
			return new Tuple2<MatrixIndexes, Tuple2<MatrixBlock,MatrixBlock>>(ixIn, new Tuple2<>(blkIn,off));
		}
	}

	private static class RDDCumOffsetFunction implements Function<Tuple2<MatrixBlock, MatrixBlock>, MatrixBlock> 
	{
		private static final long serialVersionUID = -5804080263258064743L;

		private UnaryOperator _uop = null;
		private BinaryOperator _bop = null;
		
		public RDDCumOffsetFunction(UnaryOperator uop, BinaryOperator bop) {
			_uop = uop;
			_bop = bop;
		}

		@Override
		public MatrixBlock call(Tuple2<MatrixBlock, MatrixBlock> arg0) throws Exception  {
			//prepare inputs and outputs
			MatrixBlock dblkIn = arg0._1(); //original data 
			MatrixBlock oblkIn = arg0._2(); //offset row vector
			MatrixBlock data2 = new MatrixBlock(dblkIn); //cp data
			boolean cumsumprod = _bop.fn instanceof PlusMultiply;
			
			//blockwise offset aggregation and prefix sum computation
			if( cumsumprod ) {
				data2.quickSetValue(0, 0, data2.quickGetValue(0, 0)
					+ data2.quickGetValue(0, 1) * oblkIn.quickGetValue(0, 0));
			}
			else {
				MatrixBlock fdata2 = data2.slice(0, 0);
				fdata2.binaryOperationsInPlace(_bop, oblkIn); //sum offset to first row
				data2.copy(0, 0, 0, data2.getNumColumns()-1, fdata2, true); //0-based
			}
			
			//compute columnwise prefix sums/prod/min/max
			MatrixBlock blkOut = new MatrixBlock(dblkIn.getNumRows(),
				cumsumprod ? 1 : dblkIn.getNumColumns(), dblkIn.isInSparseFormat());
			data2.unaryOperations(_uop, blkOut);

			return blkOut;
		}
	}
}
