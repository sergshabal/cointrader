package org.cryptocoinpartners.esper;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cryptocoinpartners.schema.Bar;
import org.cryptocoinpartners.schema.Market;

import com.espertech.esper.client.EventBean;
import com.espertech.esper.client.EventType;
import com.espertech.esper.core.context.util.AgentInstanceViewFactoryChainContext;
import com.espertech.esper.core.service.EPStatementHandleCallback;
import com.espertech.esper.core.service.ExtensionServicesContext;
import com.espertech.esper.epl.expression.ExprNode;
import com.espertech.esper.event.EventAdapterService;
import com.espertech.esper.schedule.ScheduleHandleCallback;
import com.espertech.esper.schedule.ScheduleSlot;
import com.espertech.esper.view.CloneableView;
import com.espertech.esper.view.View;
import com.espertech.esper.view.ViewSupport;

/**
 * Custom view to compute minute OHLC bars for double values and based on the event's timestamps.
 * <p>
 * Assumes events arrive in the order of timestamps, i.e. event 1 timestamp is always less or equal event 2 timestamp.
 * <p>
 * Implemented as a custom plug-in view rather then a series of EPL statements for the following reasons:
 *   - Custom output result mixing aggregation (min/max) and first/last values
 *   - No need for a data window retaining events if using a custom view
 *   - Unlimited number of groups (minute timestamps) makes the group-by clause hard to use
 */
public class OHLCBarPlugInView extends ViewSupport implements CloneableView {
    private final static int LATE_EVENT_SLACK_SECONDS = 5;
    private static Log log = LogFactory.getLog(OHLCBarPlugInView.class);

    private final AgentInstanceViewFactoryChainContext agentInstanceViewFactoryContext;
    private final ScheduleSlot scheduleSlot;
    private final ExprNode timestampExpression;
    private final ExprNode valueExpression;
    private ExprNode marketExpression;
    private ExprNode intervalExpression;
    private final EventBean[] eventsPerStream = new EventBean[1];

    private EPStatementHandleCallback handle;
    private Long cutoffTimestampMinute;
    private Long currentTimestampMinute;
    private Double first;
    private Double last;
    private Double max;
    private Double min;
    private Market market;
    private static Double interval;
    private EventBean lastEvent;

    public OHLCBarPlugInView(AgentInstanceViewFactoryChainContext agentInstanceViewFactoryContext, ExprNode timestampExpression, ExprNode valueExpression) {
        this.agentInstanceViewFactoryContext = agentInstanceViewFactoryContext;
        this.timestampExpression = timestampExpression;
        this.valueExpression = valueExpression;
        this.scheduleSlot = agentInstanceViewFactoryContext.getStatementContext().getScheduleBucket().allocateSlot();
    }

    public OHLCBarPlugInView(AgentInstanceViewFactoryChainContext agentInstanceViewFactoryContext, ExprNode timestampExpression, ExprNode valueExpression,
            ExprNode marketExpression, ExprNode intervalExpression) {
        this.agentInstanceViewFactoryContext = agentInstanceViewFactoryContext;
        this.timestampExpression = timestampExpression;
        this.valueExpression = valueExpression;
        this.marketExpression = marketExpression;
        this.intervalExpression = intervalExpression;
        this.scheduleSlot = agentInstanceViewFactoryContext.getStatementContext().getScheduleBucket().allocateSlot();
    }

    @Override
    public void update(EventBean[] newData, EventBean[] oldData) {
        if (newData == null) {
            return;
        }

        for (EventBean theEvent : newData) {
            eventsPerStream[0] = theEvent;
            interval = (Double) intervalExpression.getExprEvaluator().evaluate(eventsPerStream, true, agentInstanceViewFactoryContext);
            Long timestamp = (Long) timestampExpression.getExprEvaluator().evaluate(eventsPerStream, true, agentInstanceViewFactoryContext);
            Long timestampMinute = removeSeconds(timestamp);
            double value = (Double) valueExpression.getExprEvaluator().evaluate(eventsPerStream, true, agentInstanceViewFactoryContext);
            if (marketExpression != null)
                market = (Market) marketExpression.getExprEvaluator().evaluate(eventsPerStream, true, agentInstanceViewFactoryContext);
            // test if this minute has already been published, the event is too late
            if ((cutoffTimestampMinute != null) && (timestampMinute <= cutoffTimestampMinute)) {
                continue;
            }

            // if the same minute, aggregate
            if (timestampMinute.equals(currentTimestampMinute)) {
                applyValue(value);
            }
            // first time we see an event for this minute
            else {
                // there is data to post
                if (currentTimestampMinute != null) {
                    postData();
                }

                currentTimestampMinute = timestampMinute;
                applyValue(value);

                // schedule a callback to fire in case no more events arrive
                scheduleCallback();
            }
        }
    }

    @Override
    public EventType getEventType() {
        return getEventType(agentInstanceViewFactoryContext.getStatementContext().getEventAdapterService());
    }

    @Override
    public Iterator<EventBean> iterator() {
        throw new UnsupportedOperationException("Not supported");
    }

    @Override
    public View cloneView() {
        return new OHLCBarPlugInView(agentInstanceViewFactoryContext, timestampExpression, valueExpression, marketExpression, intervalExpression);
    }

    private void applyValue(double value) {
        if (first == null) {
            first = value;
        }
        last = value;
        if (min == null) {
            min = value;
        } else if (min.compareTo(value) > 0) {
            min = value;
        }
        if (max == null) {
            max = value;
        } else if (max.compareTo(value) < 0) {
            max = value;
        }
    }

    protected static EventType getEventType(EventAdapterService eventAdapterService) {
        return eventAdapterService.addBeanType(Bar.class.getName(), Bar.class, false, false, false);
    }

    private static long removeSeconds(long timestamp) {
        Calendar cal = GregorianCalendar.getInstance();
        cal.setTimeInMillis(timestamp);
        //TODO: need to support bars for mulitiple days
        if ((interval / 86400) > 1) {
            cal.set(Calendar.DAY_OF_YEAR, 0);
            // round interval to nearest day
            interval = ((double) Math.round(interval / 86400)) * 86400;
        }

        if ((interval / 3600) > 1) {
            cal.set(Calendar.HOUR_OF_DAY, 0);
            // round interval to nearest hour
            interval = ((double) Math.round(interval / 3600)) * 3600;
        }

        if ((interval / 60) > 1) {
            cal.set(Calendar.MINUTE, 0);
            // round interval to nearest day
            interval = ((double) Math.round(interval / 60)) * 60;
        }
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    private void scheduleCallback() {
        if (handle != null) {
            // remove old schedule
            agentInstanceViewFactoryContext.getStatementContext().getSchedulingService().remove(handle, scheduleSlot);
            handle = null;
        }

        long currentTime = agentInstanceViewFactoryContext.getStatementContext().getSchedulingService().getTime();
        long currentRemoveSeconds = removeSeconds(currentTime);
        //long targetTime = currentRemoveSeconds + (86400 + LATE_EVENT_SLACK_SECONDS) * 1000; // leave some seconds for late comers
        long targetTime = currentRemoveSeconds + (interval.longValue() + LATE_EVENT_SLACK_SECONDS) * 1000; // leave some seconds for late comers

        long scheduleAfterMSec = targetTime - currentTime;

        ScheduleHandleCallback callback = new ScheduleHandleCallback() {
            @Override
            public void scheduledTrigger(ExtensionServicesContext extensionServicesContext) {
                handle = null; // clear out schedule handle
                OHLCBarPlugInView.this.postData();
            }
        };

        handle = new EPStatementHandleCallback(agentInstanceViewFactoryContext.getEpStatementAgentInstanceHandle(), callback);
        agentInstanceViewFactoryContext.getStatementContext().getSchedulingService().add(scheduleAfterMSec, handle, scheduleSlot);
    }

    private void postData() {
        Bar barValue = new Bar(currentTimestampMinute, first, last, max, min, market);
        EventBean outgoing = agentInstanceViewFactoryContext.getStatementContext().getEventAdapterService().adapterForBean(barValue);
        if (lastEvent == null) {
            this.updateChildren(new EventBean[] { outgoing }, null);
        } else {
            this.updateChildren(new EventBean[] { outgoing }, new EventBean[] { lastEvent });
        }
        lastEvent = outgoing;

        cutoffTimestampMinute = currentTimestampMinute;
        first = null;
        last = null;
        max = null;
        min = null;
        currentTimestampMinute = null;
    }
}
