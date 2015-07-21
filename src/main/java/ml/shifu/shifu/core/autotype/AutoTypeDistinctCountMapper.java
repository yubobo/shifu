/*
 * Copyright [2012-2015] PayPal Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ml.shifu.shifu.core.autotype;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ml.shifu.shifu.container.obj.ColumnConfig;
import ml.shifu.shifu.container.obj.ModelConfig;
import ml.shifu.shifu.container.obj.RawSourceData.SourceType;
import ml.shifu.shifu.core.DataPurifier;
import ml.shifu.shifu.util.CommonUtils;
import ml.shifu.shifu.util.Constants;

import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Mapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.clearspring.analytics.stream.cardinality.HyperLogLogPlus;

/**
 * {@link AutoTypeDistinctCountMapper} is a mapper to get {@link HyperLogLogPlus} statistics per split. Such statistics
 * will be merged in our reducer.
 */
public class AutoTypeDistinctCountMapper extends Mapper<LongWritable, Text, IntWritable, BytesWritable> {

    private final static Logger LOG = LoggerFactory.getLogger(AutoTypeDistinctCountMapper.class);

    /**
     * Model Config read from HDFS
     */
    private ModelConfig modelConfig;

    /**
     * To filter records by customized expressions
     */
    private DataPurifier dataPurifier;

    /**
     * Output key cache to avoid new operation.
     */
    private IntWritable outputKey;

    private Map<Integer, HyperLogLogPlus> variableCountMap;

    /**
     * Tag column index
     */
    private int tagColumnNum = -1;

    /**
     * Column Config list read from HDFS
     */
    private List<ColumnConfig> columnConfigList;

    private void loadConfigFiles(final Context context) {
        try {
            SourceType sourceType = SourceType.valueOf(context.getConfiguration().get(
                    Constants.SHIFU_MODELSET_SOURCE_TYPE, SourceType.HDFS.toString()));
            this.modelConfig = CommonUtils.loadModelConfig(
                    context.getConfiguration().get(Constants.SHIFU_MODEL_CONFIG), sourceType);
            this.columnConfigList = CommonUtils.loadColumnConfigList(
                    context.getConfiguration().get(Constants.SHIFU_COLUMN_CONFIG), sourceType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
        loadConfigFiles(context);

        this.dataPurifier = new DataPurifier(this.modelConfig);

        loadTagWeightNum();

        this.variableCountMap = new HashMap<Integer, HyperLogLogPlus>();

        this.outputKey = new IntWritable();
    }

    /**
     * Load tag weight index field.
     */
    private void loadTagWeightNum() {
        for(ColumnConfig config: this.columnConfigList) {
            if(config.isTarget()) {
                this.tagColumnNum = config.getColumnNum();
                break;
            }
        }

        if(this.tagColumnNum == -1) {
            throw new RuntimeException("No valid target column.");
        }
    }

    @Override
    protected void map(LongWritable key, Text value, Context context) throws IOException, InterruptedException {
        String valueStr = value.toString();
        if(!this.dataPurifier.isFilterOut(valueStr)) {
            return;
        }

        if(valueStr == null || valueStr.length() == 0 || valueStr.trim().length() == 0) {
            LOG.warn("Empty input.");
            return;
        }

        String[] units = CommonUtils.split(valueStr, this.modelConfig.getDataSetDelimiter());
        // tagColumnNum should be in units array, if not IndexOutofBoundException
        String tag = units[this.tagColumnNum];

        if(tag == null || (!modelConfig.getPosTags().contains(tag) && !modelConfig.getNegTags().contains(tag))) {
            if(System.currentTimeMillis() % 20 == 0) {
                LOG.warn("Data with invalid tag is ignored in distinct count computing, invalid tag: {}.", tag);
            }
            return;
        }

        int i = 0;
        for(String unit: units) {
            if(unit == null || this.modelConfig.getDataSet().getMissingOrInvalidValues().contains(unit.toLowerCase())) {
                i++;
                continue;
            }
            HyperLogLogPlus hyperLogLogPlus = this.variableCountMap.get(i);
            if(hyperLogLogPlus == null) {
                hyperLogLogPlus = new HyperLogLogPlus(8);
                this.variableCountMap.put(i, hyperLogLogPlus);
            }
            hyperLogLogPlus.offer(unit);
            i++;
        }
    }

    /**
     * Write column info to reducer for merging.
     */
    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        for(Map.Entry<Integer, HyperLogLogPlus> entry: this.variableCountMap.entrySet()) {
            this.outputKey.set(entry.getKey());
            byte[] bytes = entry.getValue().getBytes();
            context.write(this.outputKey, new BytesWritable(bytes));
        }
    }
}
